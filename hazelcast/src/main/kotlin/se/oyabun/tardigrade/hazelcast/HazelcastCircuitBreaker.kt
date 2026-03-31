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
package se.oyabun.tardigrade.hazelcast

import com.hazelcast.collection.IList
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.topic.ITopic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.oyabun.tardigrade.CircuitBreaker
import se.oyabun.tardigrade.CircuitBreakerOutcome
import se.oyabun.tardigrade.CircuitBreakerState
import se.oyabun.tardigrade.Logging
import se.oyabun.tardigrade.hazelcast.extension.validateConfig
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HazelcastCircuitBreaker(
    private val name: String,
    private val hazelcast: HazelcastInstance,
    override val failureRateThreshold: Float = 50f,
    override val slidingWindowSize: Int = 100,
    override val minimumNumberOfCalls: Int = 10,
    override val waitDurationInOpenState: Duration = 60.seconds,
    override val permittedCallsInHalfOpenState: Int = 5,
) : CircuitBreaker {
    private val prefix = "tardigrade:cb:$name"
    private val stateMap: IMap<String, String> =
        hazelcast.getMap(
            "$prefix:state",
        )
    private val calls: IList<String> = hazelcast.getList("$prefix:calls")
    private val meta: IMap<String, String> = hazelcast.getMap("$prefix:meta")
    private val events: ITopic<String> = hazelcast.getTopic("$prefix:events")

    private val log = Logging.logger {}
    private val localState = AtomicReference(CircuitBreakerState.CLOSED)

    init {
        log.circuitBreaker.created("hazelcast", name).after {
            hazelcast.validateConfig("$prefix:config", name, configHash())
            meta.putIfAbsent(
                "halfopen",
                permittedCallsInHalfOpenState.toString(),
            )
            events.addMessageListener { msg ->
                localState.set(CircuitBreakerState.valueOf(msg.messageObject))
            }
        }
    }

    override suspend fun state(): CircuitBreakerState =
        withContext(Dispatchers.IO) {
            val raw =
                stateMap["v"] ?: return@withContext CircuitBreakerState.CLOSED
            CircuitBreakerState.valueOf(raw)
        }

    override suspend fun <T> execute(
        block: suspend () -> T,
    ): CircuitBreakerOutcome<T> {
        val current = localState.get()

        when (current) {
            CircuitBreakerState.OPEN -> {
                log.circuitBreaker.rejected(name).before {}
                return CircuitBreakerOutcome.Open
            }
            CircuitBreakerState.HALF_OPEN -> {
                val acquired =
                    withContext(Dispatchers.IO) {
                        meta.lock("l")
                        try {
                            val permits = meta["halfopen"]?.toInt() ?: 0
                            if (permits > 0) {
                                meta.put("halfopen", (permits - 1).toString())
                                true
                            } else {
                                false
                            }
                        } finally {
                            meta.unlock("l")
                        }
                    }
                if (!acquired) {
                    log.circuitBreaker.rejected(name).before {}
                    return CircuitBreakerOutcome.Open
                }
            }
            CircuitBreakerState.CLOSED -> Unit
        }

        return try {
            val result = block()
            val actualState = state()
            recordOutcome(actualState, success = true)
            CircuitBreakerOutcome.Ok(result)
        } catch (e: Throwable) {
            val actualState = state()
            recordOutcome(actualState, success = false)
            throw e
        }
    }

    private suspend fun recordOutcome(
        priorState: CircuitBreakerState,
        success: Boolean,
    ) = withContext(Dispatchers.IO) {
        val result = if (success) "SUCCESS" else "FAILURE"
        val entry = "${UUID.randomUUID()}:$result"
        meta.lock("l")
        try {
            calls.add(entry)
            while (calls.size > slidingWindowSize) calls.removeAt(0)

            when (priorState) {
                CircuitBreakerState.HALF_OPEN -> {
                    val permits = meta["halfopen"]?.toInt() ?: 0
                    meta.put("halfopen", (permits + 1).toString())
                    doTransition(
                        if (success) {
                            CircuitBreakerState.CLOSED
                        } else {
                            CircuitBreakerState.OPEN
                        },
                    )
                }
                CircuitBreakerState.CLOSED -> {
                    val allEntries = calls.toList()
                    if (allEntries.size >= minimumNumberOfCalls) {
                        val failures =
                            allEntries.count {
                                it.endsWith(
                                    ":FAILURE",
                                )
                            }
                        val rate = (failures.toFloat() / allEntries.size) * 100f
                        if (rate >=
                            failureRateThreshold
                        ) {
                            doTransition(CircuitBreakerState.OPEN)
                        }
                    }
                }
                else -> Unit
            }
        } finally {
            meta.unlock("l")
        }
    }

    private fun doTransition(target: CircuitBreakerState) {
        val current =
            stateMap["v"]?.let { CircuitBreakerState.valueOf(it) }
                ?: CircuitBreakerState.CLOSED
        if (current == target) return

        when (target) {
            CircuitBreakerState.OPEN -> {
                stateMap.put(
                    "v",
                    target.name,
                    waitDurationInOpenState.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                )
                calls.clear()
                meta.put("halfopen", permittedCallsInHalfOpenState.toString())
            }
            CircuitBreakerState.HALF_OPEN -> {
                stateMap.put("v", target.name)
                meta.put("halfopen", permittedCallsInHalfOpenState.toString())
            }
            CircuitBreakerState.CLOSED -> {
                stateMap.remove("v")
                calls.clear()
            }
        }

        log.circuitBreaker.transition(name, target)
        localState.set(target)
        events.publish(target.name)
    }
}
