/*
 *   Copyright 2026 Daniel Sundberg, Oyabun AB
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package se.oyabun.tardigrade.redis

import kotlinx.coroutines.future.await
import org.redisson.api.RBucket
import org.redisson.api.RLock
import org.redisson.api.RScoredSortedSet
import org.redisson.api.RSemaphore
import org.redisson.api.RTopic
import org.redisson.api.RedissonClient
import se.oyabun.tardigrade.CircuitBreaker
import se.oyabun.tardigrade.CircuitBreakerOutcome
import se.oyabun.tardigrade.CircuitBreakerState
import se.oyabun.tardigrade.Logging
import se.oyabun.tardigrade.redis.extension.validateConfig
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class RedisCircuitBreaker(
    private val name: String,
    private val redisson: RedissonClient,
    override val failureRateThreshold: Float = 50f,
    override val slidingWindowSize: Int = 100,
    override val minimumNumberOfCalls: Int = 10,
    override val waitDurationInOpenState: Duration = 60.seconds,
    override val permittedCallsInHalfOpenState: Int = 5,
) : CircuitBreaker {
    private val prefix = "tardigrade:cb:$name"
    private val stateBucket: RBucket<String> =
        redisson.getBucket(
            "$prefix:state",
        )
    private val calls: RScoredSortedSet<String> =
        redisson.getScoredSortedSet(
            "$prefix:calls",
        )
    private val halfOpenSemaphore: RSemaphore =
        redisson.getSemaphore(
            "$prefix:halfopen",
        )
    private val lock: RLock = redisson.getLock("$prefix:lock")
    private val events: RTopic = redisson.getTopic("$prefix:events")

    private val log = Logging.logger {}
    private val localState = AtomicReference(CircuitBreakerState.CLOSED)

    init {
        redisson.validateConfig("$prefix:config", name, configHash())
        halfOpenSemaphore.trySetPermits(permittedCallsInHalfOpenState)
        events.addListener(String::class.java) { _, msg ->
            localState.set(CircuitBreakerState.valueOf(msg))
        }
        log.circuitBreaker.created("redis", name).before {}
    }

    override suspend fun state(): CircuitBreakerState {
        val raw =
            stateBucket.getAsync().await() ?: return CircuitBreakerState.CLOSED
        return CircuitBreakerState.valueOf(raw)
    }

    override suspend fun <T> execute(
        block: suspend () -> T,
    ): CircuitBreakerOutcome<T> {
        val current = state()
        localState.set(current)

        when (current) {
            CircuitBreakerState.OPEN -> {
                log.circuitBreaker.rejected(name).before {}
                return CircuitBreakerOutcome.Open
            }
            CircuitBreakerState.HALF_OPEN -> {
                val acquired = halfOpenSemaphore.tryAcquireAsync().await()
                if (!acquired) {
                    log.circuitBreaker.rejected(name).before {}
                    return CircuitBreakerOutcome.Open
                }
            }
            CircuitBreakerState.CLOSED -> Unit
        }

        return try {
            val result = block()
            recordOutcome(current, success = true)
            CircuitBreakerOutcome.Ok(result)
        } catch (e: Throwable) {
            recordOutcome(current, success = false)
            throw e
        }
    }

    private suspend fun recordOutcome(
        priorState: CircuitBreakerState,
        success: Boolean,
    ) {
        val now =
            TimeSource.Monotonic
                .markNow()
                .elapsedNow()
                .inWholeNanoseconds
                .toDouble()
        val result = if (success) "SUCCESS" else "FAILURE"
        val entry = "${UUID.randomUUID()}:$result"

        calls.addAsync(now, entry).await()
        trimToSlidingWindow()

        when {
            priorState == CircuitBreakerState.HALF_OPEN && success -> {
                transitionTo(CircuitBreakerState.CLOSED)
                halfOpenSemaphore.releaseAsync().await()
            }
            priorState == CircuitBreakerState.HALF_OPEN && !success -> {
                transitionTo(CircuitBreakerState.OPEN)
                halfOpenSemaphore.releaseAsync().await()
            }
            priorState == CircuitBreakerState.CLOSED -> maybeTrip()
            else -> Unit
        }
    }

    private suspend fun trimToSlidingWindow() {
        val size = calls.sizeAsync().await()
        if (size > slidingWindowSize) {
            calls
                .removeRangeByRankAsync(
                    0,
                    size - slidingWindowSize - 1,
                ).await()
        }
    }

    private suspend fun maybeTrip() {
        val allEntries = calls.readAllAsync().await()
        if (allEntries.size < minimumNumberOfCalls) return
        val failures = allEntries.count { it.endsWith(":FAILURE") }
        val rate = (failures.toFloat() / allEntries.size) * 100f
        if (rate >= failureRateThreshold) transitionTo(CircuitBreakerState.OPEN)
    }

    private suspend fun transitionTo(target: CircuitBreakerState) {
        val locked = lock.tryLockAsync(0, TimeUnit.MILLISECONDS).await()
        if (!locked) return
        try {
            val currentRaw = stateBucket.getAsync().await()
            val current =
                currentRaw?.let { CircuitBreakerState.valueOf(it) }
                    ?: CircuitBreakerState.CLOSED
            if (current == target) return

            when (target) {
                CircuitBreakerState.OPEN -> {
                    stateBucket
                        .setAsync(
                            target.name,
                            waitDurationInOpenState.inWholeMilliseconds,
                            TimeUnit.MILLISECONDS,
                        ).await()
                    calls.deleteAsync().await()
                    halfOpenSemaphore
                        .trySetPermitsAsync(
                            permittedCallsInHalfOpenState,
                        ).await()
                }
                CircuitBreakerState.HALF_OPEN -> {
                    stateBucket.setAsync(target.name).await()
                    halfOpenSemaphore
                        .trySetPermitsAsync(
                            permittedCallsInHalfOpenState,
                        ).await()
                }
                CircuitBreakerState.CLOSED -> {
                    stateBucket.deleteAsync().await()
                    calls.deleteAsync().await()
                }
            }

            log.circuitBreaker.transition(name, target).before {}
            localState.set(target)
            events.publishAsync(target.name).await()
        } finally {
            lock.unlockAsync().await()
        }
    }
}
