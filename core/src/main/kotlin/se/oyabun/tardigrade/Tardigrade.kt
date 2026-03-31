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
package se.oyabun.tardigrade

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType
import io.github.resilience4j.core.IntervalFunction
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

typealias CircuitBreakerProvider =
    (String, Float, Int, Int, Duration, Int) -> CircuitBreaker
typealias RateLimitProvider = (String, Long, Duration, Duration) -> RateLimiter
typealias BulkheadProvider = (String, Int, Duration) -> Bulkhead
typealias RetryProvider = (
    String,
    Int,
    Duration,
    Double,
    Set<KClass<out Throwable>>,
) -> Retry

@Suppress("ClassName")
object Tardigrade {
    private val log = Logging.logger {}

    private val usedDistributions = ConcurrentHashMap.newKeySet<Distribution>()

    private val cbInstances = ConcurrentHashMap<String, CircuitBreaker>()
    private val rlInstances = ConcurrentHashMap<String, RateLimiter>()
    private val bhInstances = ConcurrentHashMap<String, Bulkhead>()
    private val retryInstances = ConcurrentHashMap<String, Retry>()

    private val cbUserHashes = ConcurrentHashMap<String, String>()
    private val rlUserHashes = ConcurrentHashMap<String, String>()
    private val bhUserHashes = ConcurrentHashMap<String, String>()
    private val retryUserHashes = ConcurrentHashMap<String, String>()

    private fun use(
        distribution: Distribution,
        algorithm: DistributionAlgorithm,
    ) {
        usedDistributions.add(distribution)
        if (algorithm.requiresInstanceCount) distribution.register()
    }

    internal const val IN_MEMORY = "in-memory"

    fun reset() =
        log.factory.reset().after {
            usedDistributions.forEach { it.deregister() }
            usedDistributions.clear()
            cbInstances.clear()
            cbUserHashes.clear()
            rlInstances.clear()
            rlUserHashes.clear()
            bhInstances.clear()
            bhUserHashes.clear()
            retryInstances.clear()
            retryUserHashes.clear()
        }

    object define {
        fun circuitBreaker(
            name: String,
            failureRateThreshold: Float = 50f,
            slidingWindowSize: Int = 100,
            minimumNumberOfCalls: Int = 10,
            waitDurationInOpenState: Duration = 60.seconds,
            permittedCallsInHalfOpenState: Int = 5,
            algorithm: DistributionAlgorithm = DistributionAlgorithm.AsIs,
            distribution: Distribution = InMemoryDistribution,
        ): CircuitBreaker {
            use(distribution, algorithm)
            val count =
                if (algorithm.requiresInstanceCount) {
                    distribution
                        .instanceCount()
                } else {
                    1
                }
            val (scaledMin, scaledWindow) =
                algorithm.scaleCircuitBreaker(
                    minimumNumberOfCalls,
                    slidingWindowSize,
                    count,
                )
            val cb =
                cbInstances.computeIfAbsent(name) {
                    distribution.circuitBreakers(
                        name,
                        failureRateThreshold,
                        scaledWindow,
                        scaledMin,
                        waitDurationInOpenState,
                        permittedCallsInHalfOpenState,
                    )
                }
            val hash =
                arrayOf(
                    failureRateThreshold.toString(),
                    slidingWindowSize.toString(),
                    minimumNumberOfCalls.toString(),
                    waitDurationInOpenState.inWholeMilliseconds.toString(),
                    permittedCallsInHalfOpenState.toString(),
                    algorithm::class.qualifiedName ?: algorithm::class.simpleName,
                    distribution::class.qualifiedName ?: distribution::class.simpleName,
                ).contentHashCode().toString(16)
            val priorCb = cbUserHashes.putIfAbsent(name, hash)
            if (priorCb != null &&
                priorCb != hash
            ) {
                throw ConfigConflictException(name, hash, priorCb)
            }
            return cb
        }

        fun rateLimiter(
            name: String,
            limitForPeriod: Long = 50,
            limitRefreshPeriod: Duration = 1.seconds,
            timeoutDuration: Duration = Duration.ZERO,
            algorithm: DistributionAlgorithm = DistributionAlgorithm.AsIs,
            distribution: Distribution = InMemoryDistribution,
        ): RateLimiter {
            use(distribution, algorithm)
            val count =
                if (algorithm.requiresInstanceCount) {
                    distribution
                        .instanceCount()
                } else {
                    1
                }
            val scaledLimit = algorithm.scaleRateLimit(limitForPeriod, count)
            val rl =
                rlInstances.computeIfAbsent(name) {
                    distribution.rateLimiters(
                        name,
                        scaledLimit,
                        limitRefreshPeriod,
                        timeoutDuration,
                    )
                }
            val hash =
                arrayOf(
                    limitForPeriod.toString(),
                    limitRefreshPeriod.inWholeMilliseconds.toString(),
                    algorithm::class.qualifiedName ?: algorithm::class.simpleName,
                    distribution::class.qualifiedName ?: distribution::class.simpleName,
                ).contentHashCode().toString(16)
            val priorRl = rlUserHashes.putIfAbsent(name, hash)
            if (priorRl != null &&
                priorRl != hash
            ) {
                throw ConfigConflictException(name, hash, priorRl)
            }
            return rl
        }

        fun bulkhead(
            name: String,
            maxConcurrentCalls: Int = 25,
            maxWaitDuration: Duration = Duration.ZERO,
            algorithm: DistributionAlgorithm = DistributionAlgorithm.AsIs,
            distribution: Distribution = InMemoryDistribution,
        ): Bulkhead {
            use(distribution, algorithm)
            val count =
                if (algorithm.requiresInstanceCount) {
                    distribution
                        .instanceCount()
                } else {
                    1
                }
            val scaledCalls = algorithm.scaleBulkhead(maxConcurrentCalls, count)
            val bh =
                bhInstances.computeIfAbsent(name) {
                    distribution.bulkheads(name, scaledCalls, maxWaitDuration)
                }
            val hash =
                arrayOf(
                    maxConcurrentCalls.toString(),
                    algorithm::class.qualifiedName ?: algorithm::class.simpleName,
                    distribution::class.qualifiedName ?: distribution::class.simpleName,
                ).contentHashCode().toString(16)
            val priorBh = bhUserHashes.putIfAbsent(name, hash)
            if (priorBh != null &&
                priorBh != hash
            ) {
                throw ConfigConflictException(name, hash, priorBh)
            }
            return bh
        }

        fun retry(
            name: String,
            maxAttempts: Int = 3,
            waitDuration: Duration = 500.milliseconds,
            exponentialBackoffMultiplier: Double = 1.0,
            retryOn: Set<KClass<out Throwable>> = setOf(Exception::class),
            distribution: Distribution = InMemoryDistribution,
        ): Retry {
            use(distribution, DistributionAlgorithm.AsIs)
            val r =
                retryInstances.computeIfAbsent(name) {
                    distribution.retries(
                        name,
                        maxAttempts,
                        waitDuration,
                        exponentialBackoffMultiplier,
                        retryOn,
                    )
                }
            val hash =
                arrayOf(
                    maxAttempts.toString(),
                    waitDuration.inWholeMilliseconds.toString(),
                    exponentialBackoffMultiplier.toString(),
                    retryOn.sortedBy { it.qualifiedName.orEmpty() }.toString(),
                    distribution::class.qualifiedName ?: distribution::class.simpleName,
                ).contentHashCode().toString(16)
            val priorRetry = retryUserHashes.putIfAbsent(name, hash)
            if (priorRetry != null &&
                priorRetry != hash
            ) {
                throw ConfigConflictException(name, hash, priorRetry)
            }
            return r
        }
    }

    object circuitBreaker {
        operator fun get(name: String): CircuitBreaker =
            cbInstances[name]
                ?: throw IllegalStateException(
                    "CircuitBreaker '$name' is not defined.",
                )
    }

    object rateLimiter {
        operator fun get(name: String): RateLimiter =
            rlInstances[name]
                ?: throw IllegalStateException(
                    "RateLimiter '$name' is not defined.",
                )
    }

    object bulkhead {
        operator fun get(name: String): Bulkhead =
            bhInstances[name]
                ?: throw IllegalStateException(
                    "Bulkhead '$name' is not defined.",
                )
    }

    object retry {
        operator fun get(name: String): Retry =
            retryInstances[name]
                ?: throw IllegalStateException("Retry '$name' is not defined.")
    }
}

internal class InMemoryCircuitBreaker(
    private val name: String,
    override val failureRateThreshold: Float = 50f,
    override val slidingWindowSize: Int = 100,
    override val minimumNumberOfCalls: Int = 10,
    override val waitDurationInOpenState: Duration = 60.seconds,
    override val permittedCallsInHalfOpenState: Int = 5,
) : CircuitBreaker {
    private val log = Logging.logger {}

    init {
        log.circuitBreaker.created(Tardigrade.IN_MEMORY, name).before {}
    }

    private val cb =
        io.github.resilience4j.circuitbreaker.CircuitBreaker.of(
            name,
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
                .custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(
                    waitDurationInOpenState.toJavaDuration(),
                ).permittedNumberOfCallsInHalfOpenState(
                    permittedCallsInHalfOpenState,
                ).build(),
        )

    override suspend fun state(): CircuitBreakerState =
        when (cb.state) {
            State.CLOSED -> CircuitBreakerState.CLOSED
            State.OPEN,
            State.FORCED_OPEN,
            -> CircuitBreakerState.OPEN
            State.HALF_OPEN -> CircuitBreakerState.HALF_OPEN
            else -> CircuitBreakerState.CLOSED
        }

    override suspend fun <T> execute(
        block: suspend () -> T,
    ): CircuitBreakerOutcome<T> =
        runCatching { cb.acquirePermission() }.fold(
            onSuccess = {
                val start = TimeSource.Monotonic.markNow()
                runCatching { block() }
                    .onSuccess {
                        cb.onSuccess(
                            start.elapsedNow().inWholeNanoseconds,
                            TimeUnit.NANOSECONDS,
                        )
                    }.fold(
                        onSuccess = { CircuitBreakerOutcome.Ok(it) },
                        onFailure = { e ->
                            cb.onError(
                                start.elapsedNow().inWholeNanoseconds,
                                TimeUnit.NANOSECONDS,
                                e,
                            )
                            throw e
                        },
                    )
            },
            onFailure = { issue ->
                when {
                    issue is CallNotPermittedException -> {
                        log.circuitBreaker.rejected(name).before {}
                        CircuitBreakerOutcome.Open
                    }
                    else -> throw issue
                }
            },
        )
}

internal class InMemoryRateLimiter(
    private val name: String,
    override val limitForPeriod: Long = 50,
    override val limitRefreshPeriod: Duration = 1.seconds,
    override val timeoutDuration: Duration = Duration.ZERO,
) : RateLimiter {
    private val log = Logging.logger {}

    init {
        log.rateLimiter.created(Tardigrade.IN_MEMORY, name).before {}
    }

    private val rl =
        io.github.resilience4j.ratelimiter.RateLimiter.of(
            name,
            io.github.resilience4j.ratelimiter.RateLimiterConfig
                .custom()
                .limitForPeriod(limitForPeriod.toInt())
                .limitRefreshPeriod(limitRefreshPeriod.toJavaDuration())
                .timeoutDuration(timeoutDuration.toJavaDuration())
                .build(),
        )

    override suspend fun <T> execute(
        block: suspend () -> T,
    ): RateLimiterOutcome<T> {
        val waitNanos = rl.reservePermission()
        if (waitNanos < 0) {
            log.rateLimiter.exceeded(name).before {}
            return RateLimiterOutcome.Exceeded
        }
        if (waitNanos > 0) delay(waitNanos.nanoseconds)
        return RateLimiterOutcome.Ok(block())
    }
}

internal class InMemoryBulkhead(
    private val name: String,
    override val maxConcurrentCalls: Int = 25,
    override val maxWaitDuration: Duration = Duration.ZERO,
) : Bulkhead {
    private val log = Logging.logger {}

    init {
        log.bulkhead.created(Tardigrade.IN_MEMORY, name).before {}
    }

    private val bh =
        io.github.resilience4j.bulkhead.Bulkhead.of(
            name,
            io.github.resilience4j.bulkhead.BulkheadConfig
                .custom()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxWaitDuration(maxWaitDuration.toJavaDuration())
                .build(),
        )

    override suspend fun <T> execute(
        block: suspend () -> T,
    ): BulkheadOutcome<T> {
        try {
            bh.acquirePermission()
        } catch (_: io.github.resilience4j.bulkhead.BulkheadFullException) {
            log.bulkhead.full(name).before {}
            return BulkheadOutcome.Full
        }
        return BulkheadOutcome.Ok(
            try {
                block()
            } finally {
                bh.releasePermission()
            },
        )
    }
}

internal class InMemoryRetry(
    private val name: String,
    override val maxAttempts: Int = 3,
    override val waitDuration: Duration = 500.milliseconds,
    override val exponentialBackoffMultiplier: Double = 1.0,
    override val retryOn: Set<KClass<out Throwable>> = setOf(Exception::class),
) : Retry {
    private val log = Logging.logger {}

    init {
        log.retry.created(Tardigrade.IN_MEMORY, name).before {}
    }

    private val intervalFn: IntervalFunction =
        if (exponentialBackoffMultiplier ==
            1.0
        ) {
            IntervalFunction.of(waitDuration.toJavaDuration())
        } else {
            IntervalFunction.ofExponentialBackoff(
                waitDuration.toJavaDuration(),
                exponentialBackoffMultiplier,
            )
        }

    override suspend fun <T> execute(block: suspend () -> T): RetryOutcome<T> {
        repeat(maxAttempts) { attempt ->
            if (attempt > 0) delay(intervalFn.apply(attempt).milliseconds)
            runCatching { return RetryOutcome.Ok(block()) }.onFailure { e ->
                if (retryOn.none { it.isInstance(e) }) throw e
            }
        }
        log.retry.exhausted(name).before {}
        return RetryOutcome.Exhausted
    }
}
