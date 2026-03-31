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

import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Flux.fromIterable
import reactor.core.publisher.Flux.range
import reactor.core.publisher.Mono
import reactor.core.publisher.Mono.empty
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier.create
import se.oyabun.tardigrade.Tardigrade.bulkhead
import se.oyabun.tardigrade.Tardigrade.circuitBreaker
import se.oyabun.tardigrade.Tardigrade.define
import se.oyabun.tardigrade.Tardigrade.rateLimiter
import se.oyabun.tardigrade.extension.withBulkhead
import se.oyabun.tardigrade.extension.withCircuitBreaker
import se.oyabun.tardigrade.extension.withRateLimiter
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class CircuitBreakerDefinition(
    val name: String,
    val failureRateThreshold: Float = 50f,
    val slidingWindowSize: Int = 100,
    val minimumNumberOfCalls: Int = 10,
    val waitDurationInOpenState: Duration = 60.seconds,
    val permittedCallsInHalfOpenState: Int = 5,
)

data class RateLimiterDefinition(
    val name: String,
    val limitForPeriod: Long = 50,
    val limitRefreshPeriod: Duration = 1.seconds,
    val timeoutDuration: Duration = Duration.ZERO,
)

data class BulkheadDefinition(
    val name: String,
    val maxConcurrentCalls: Int = 25,
    val maxWaitDuration: Duration = Duration.ZERO,
)

abstract class TardigradeContractTest {
    @BeforeEach fun initTardigrade() {
        setup()
        initComponents(
            circuitBreakers =
                listOf(
                    CircuitBreakerDefinition("cb-closed"),
                    CircuitBreakerDefinition(
                        "cb-trip",
                        failureRateThreshold = 50f,
                        minimumNumberOfCalls = 4,
                        slidingWindowSize = 4,
                        waitDurationInOpenState = 30.seconds,
                    ),
                    CircuitBreakerDefinition(
                        "cb-reject",
                        failureRateThreshold = 50f,
                        minimumNumberOfCalls = 2,
                        slidingWindowSize = 2,
                        waitDurationInOpenState = 30.seconds,
                    ),
                    CircuitBreakerDefinition(
                        "cb-concurrent",
                        failureRateThreshold = 50f,
                        minimumNumberOfCalls = 4,
                        slidingWindowSize = 4,
                        waitDurationInOpenState = 30.seconds,
                    ),
                    CircuitBreakerDefinition(
                        "cb-reads",
                        failureRateThreshold = 50f,
                        minimumNumberOfCalls = 4,
                        slidingWindowSize = 4,
                        waitDurationInOpenState = 30.seconds,
                    ),
                    CircuitBreakerDefinition(
                        "compose-cb",
                        minimumNumberOfCalls = 100,
                    ),
                    CircuitBreakerDefinition(
                        "ext-mono-cb",
                        minimumNumberOfCalls = 100,
                    ),
                    CircuitBreakerDefinition(
                        "ext-mono-cb-open",
                        failureRateThreshold = 100f,
                        slidingWindowSize = 1,
                        minimumNumberOfCalls = 1,
                        waitDurationInOpenState = 60.seconds,
                    ),
                    CircuitBreakerDefinition(
                        "ext-flux-cb",
                        minimumNumberOfCalls = 100,
                    ),
                ),
            rateLimiters =
                listOf(
                    RateLimiterDefinition(
                        "rl-allow",
                        limitForPeriod = 5,
                        limitRefreshPeriod = 10.seconds,
                    ),
                    RateLimiterDefinition(
                        "rl-exceed",
                        limitForPeriod = 3,
                        limitRefreshPeriod = 60.seconds,
                    ),
                    RateLimiterDefinition(
                        "rl-burst",
                        limitForPeriod = 5,
                        limitRefreshPeriod = 60.seconds,
                    ),
                    RateLimiterDefinition(
                        "compose-rl",
                        limitForPeriod = 10,
                        limitRefreshPeriod = 60.seconds,
                    ),
                    RateLimiterDefinition(
                        "ext-mono-rl",
                        limitForPeriod = 5,
                        limitRefreshPeriod = 60.seconds,
                    ),
                    RateLimiterDefinition(
                        "ext-mono-rl-exceed",
                        limitForPeriod = 1,
                        limitRefreshPeriod = 60.seconds,
                    ),
                    RateLimiterDefinition(
                        "ext-flux-rl",
                        limitForPeriod = 10,
                        limitRefreshPeriod = 60.seconds,
                    ),
                ),
            bulkheads =
                listOf(
                    BulkheadDefinition("bh-allow", maxConcurrentCalls = 3),
                    BulkheadDefinition("bh-full", maxConcurrentCalls = 1),
                    BulkheadDefinition("bh-overflow", maxConcurrentCalls = 3),
                    BulkheadDefinition("ext-mono-bh", maxConcurrentCalls = 5),
                    BulkheadDefinition("ext-flux-bh", maxConcurrentCalls = 10),
                ),
        )
    }

    protected abstract fun initComponents(
        circuitBreakers: List<CircuitBreakerDefinition>,
        rateLimiters: List<RateLimiterDefinition>,
        bulkheads: List<BulkheadDefinition>,
    )

    @AfterEach fun resetTardigrade() = teardown()

    protected abstract fun setup()

    protected abstract fun teardown()

    @Test fun `circuit breaker starts CLOSED`() {
        create(mono { circuitBreaker["cb-closed"].state() })
            .expectNext(CircuitBreakerState.CLOSED)
            .verifyComplete()
    }

    @Test fun `suspend circuit breaker trips OPEN after failure threshold`() {
        val pipeline =
            fromIterable(listOf(false, false, false, true))
                .concatMap { succeeds ->
                    mono {
                        circuitBreaker["cb-trip"].execute {
                            if (succeeds) {
                                "ok"
                            } else {
                                throw RuntimeException(
                                    "fail",
                                )
                            }
                        }
                    }.onErrorResume { empty() }
                }.then(mono { circuitBreaker["cb-trip"].state() })
        create(pipeline).expectNext(CircuitBreakerState.OPEN).verifyComplete()
    }

    @Test fun `suspend circuit breaker rejects calls when OPEN`() {
        val pipeline =
            range(1, 2)
                .concatMap {
                    mono {
                        circuitBreaker["cb-reject"].execute {
                            throw RuntimeException(
                                "fail",
                            )
                        }
                    }.onErrorResume { empty() }
                }.then(
                    mono {
                        circuitBreaker["cb-reject"].execute {
                            "should not reach"
                        }
                    },
                )
        create(pipeline)
            .expectNextMatches {
                it is CircuitBreakerOutcome.Open
            }.verifyComplete()
    }

    @Test fun `circuit breaker trips from concurrent failures`() {
        val pipeline =
            range(1, 4)
                .parallel()
                .runOn(Schedulers.parallel())
                .flatMap {
                    mono {
                        circuitBreaker["cb-concurrent"].execute {
                            throw RuntimeException(
                                "fail",
                            )
                        }
                    }.onErrorResume { empty() }
                }.sequential()
                .then(mono { circuitBreaker["cb-concurrent"].state() })
        create(pipeline).expectNext(CircuitBreakerState.OPEN).verifyComplete()
    }

    @Test fun `circuit breaker state is consistent under concurrent reads`() {
        val writes =
            range(1, 4)
                .parallel()
                .runOn(Schedulers.parallel())
                .flatMap {
                    mono {
                        circuitBreaker["cb-reads"].execute {
                            throw RuntimeException(
                                "fail",
                            )
                        }
                    }.onErrorResume { empty() }
                }.sequential()
                .then(Mono.just(true))

        val reads =
            range(1, 20)
                .parallel()
                .runOn(Schedulers.parallel())
                .flatMap { mono { circuitBreaker["cb-reads"].state() } }
                .sequential()
                .collectList()

        create(Mono.zip(writes, reads).map { it.t2 })
            .expectNextMatches { states ->
                states.all {
                    it == CircuitBreakerState.CLOSED ||
                        it == CircuitBreakerState.OPEN
                }
            }.verifyComplete()
    }

    @Test fun `rate limiter allows calls within limit`() {
        val pipeline =
            range(1, 5)
                .flatMap { mono { rateLimiter["rl-allow"].execute { "ok" } } }
                .collectList()

        create(pipeline)
            .expectNextMatches { results ->
                results.size == 5 &&
                    results.all { r ->
                        r is RateLimiterOutcome.Ok<*> &&
                            r.value == "ok"
                    }
            }.verifyComplete()
    }

    @Test fun `rate limiter rejects calls over limit`() {
        val pipeline =
            range(1, 3)
                .concatMap {
                    mono { rateLimiter["rl-exceed"].execute { "ok" } }
                }.then(
                    mono { rateLimiter["rl-exceed"].execute { "over limit" } },
                )

        create(pipeline)
            .expectNextMatches {
                it is RateLimiterOutcome.Exceeded
            }.verifyComplete()
    }

    @Test fun `rate limiter enforces exact limit under concurrent burst`() {
        val pipeline =
            range(1, 10)
                .parallel()
                .runOn(Schedulers.parallel())
                .flatMap {
                    mono { rateLimiter["rl-burst"].execute { "ok" } }
                        .map {
                            if (it is RateLimiterOutcome.Ok<*>) {
                                "ok"
                            } else {
                                "rejected"
                            }
                        }
                }.sequential()
                .collectList()

        create(pipeline)
            .expectNextMatches { results ->
                results.count { it == "ok" } == 5 &&
                    results.count { it == "rejected" } == 5
            }.verifyComplete()
    }

    @Test fun `bulkhead allows concurrent calls within limit`() {
        val pipeline =
            range(1, 3)
                .parallel()
                .runOn(Schedulers.parallel())
                .flatMap { i ->
                    mono { bulkhead["bh-allow"].execute { "ok-$i" } }
                }.sequential()
                .collectList()

        create(pipeline)
            .expectNextMatches {
                it.size == 3 &&
                    it.all { it is BulkheadOutcome.Ok<*> }
            }.verifyComplete()
    }

    @Test fun `bulkhead rejects calls when full`() {
        val holder =
            mono {
                bulkhead["bh-full"].execute {
                    delay(500)
                    "done"
                }
            }
        val overflow =
            mono {
                delay(50)
                bulkhead["bh-full"].execute { "overflow" }
            }

        create(Mono.zip(holder, overflow))
            .expectNextMatches {
                it.t1 is BulkheadOutcome.Ok<*> &&
                    it.t2 is BulkheadOutcome.Full
            }.verifyComplete()
    }

    @Test fun `bulkhead rejects overflow under concurrent load`() {
        val pipeline =
            range(1, 5)
                .flatMap {
                    mono {
                        bulkhead["bh-overflow"].execute {
                            delay(200)
                            "ok"
                        }
                    }.map {
                        if (it is BulkheadOutcome.Ok<*>) {
                            it.value
                        } else {
                            "rejected"
                        }
                    }
                }.collectList()

        create(pipeline)
            .expectNextMatches { results ->
                results.count { it == "ok" } == 3 &&
                    results.count { it == "rejected" } == 2
            }.verifyComplete()
    }

    @Test fun `rate limiter and circuit breaker compose`() {
        val pipeline =
            mono {
                val cb = circuitBreaker["compose-cb"].execute { "ok" }
                check(cb is CircuitBreakerOutcome.Ok<*>)
                val rl = rateLimiter["compose-rl"].execute { cb.value }
                check(rl is RateLimiterOutcome.Ok<*>)
                rl.value
            }.flatMap { result ->
                mono { circuitBreaker["compose-cb"].state() }.map { state ->
                    result to
                        state
                }
            }

        create(pipeline)
            .expectNextMatches { (result, state) ->
                result == "ok" &&
                    state == CircuitBreakerState.CLOSED
            }.verifyComplete()
    }

    @Test fun `reactor mono withCircuitBreaker passes value through`() {
        create(
            Mono.just("ok").withCircuitBreaker(circuitBreaker["ext-mono-cb"]),
        ).expectNextMatches {
            it is CircuitBreakerOutcome.Ok<*> &&
                it.value == "ok"
        }.verifyComplete()
    }

    @Test fun `reactor mono withCircuitBreaker yields Open when open`() {
        val cb = circuitBreaker["ext-mono-cb-open"]
        val pipeline =
            Mono
                .error<String>(RuntimeException("fail"))
                .withCircuitBreaker(cb)
                .onErrorResume { empty() }
                .then(Mono.just("next").withCircuitBreaker(cb))

        create(pipeline).expectNext(CircuitBreakerOutcome.Open).verifyComplete()
    }

    @Test
    fun `reactor mono withRateLimiter passes value through within limit`() {
        create(Mono.just("ok").withRateLimiter(rateLimiter["ext-mono-rl"]))
            .expectNextMatches {
                it is RateLimiterOutcome.Ok<*> &&
                    it.value == "ok"
            }.verifyComplete()
    }

    @Test
    fun `reactor mono withRateLimiter yields Exceeded when limit exceeded`() {
        val rl = rateLimiter["ext-mono-rl-exceed"]
        val pipeline =
            Mono
                .just("first")
                .withRateLimiter(rl)
                .then(Mono.just("second").withRateLimiter(rl))

        create(
            pipeline,
        ).expectNext(RateLimiterOutcome.Exceeded).verifyComplete()
    }

    @Test
    fun `reactor mono withBulkhead passes value through within capacity`() {
        create(Mono.just("ok").withBulkhead(bulkhead["ext-mono-bh"]))
            .expectNextMatches {
                it is BulkheadOutcome.Ok<*> && it.value == "ok"
            }.verifyComplete()
    }

    @Test
    fun `reactor flux withCircuitBreaker processes all items when CLOSED`() {
        create(
            Flux
                .just(
                    "a",
                    "b",
                    "c",
                ).withCircuitBreaker(circuitBreaker["ext-flux-cb"])
                .collectList(),
        ).expectNextMatches {
            it.all { item ->
                item is CircuitBreakerOutcome.Ok<*>
            }
        }.verifyComplete()
    }

    @Test fun `reactor flux withRateLimiter processes items within limit`() {
        create(
            range(1, 5)
                .map {
                    "item-$it"
                }.withRateLimiter(rateLimiter["ext-flux-rl"])
                .collectList(),
        ).expectNextMatches {
            it.size == 5 &&
                it.all { item -> item is RateLimiterOutcome.Ok<*> }
        }.verifyComplete()
    }

    @Test fun `reactor flux withBulkhead processes items within capacity`() {
        val bh = bulkhead["ext-flux-bh"]
        val test =
            range(1, 5)
                .parallel()
                .runOn(Schedulers.parallel())
                .flatMap { Flux.just("item-$it").withBulkhead(bh) }
                .sequential()
                .collectList()
        create(test)
            .expectNextMatches {
                it.size == 5 &&
                    it.all { item -> item is BulkheadOutcome.Ok<*> }
            }.verifyComplete()
    }

    @Test fun `circuit breaker throws on config conflict`() {
        define.circuitBreaker(
            "cb-conflict",
            failureRateThreshold = 50f,
            minimumNumberOfCalls = 5,
        )
        assertFailsWith<ConfigConflictException> {
            define.circuitBreaker(
                "cb-conflict",
                failureRateThreshold = 80f,
                minimumNumberOfCalls = 5,
            )
        }
    }

    @Test fun `rate limiter throws on config conflict`() {
        define.rateLimiter(
            "rl-conflict",
            limitForPeriod = 10,
            limitRefreshPeriod = 60.seconds,
        )
        assertFailsWith<ConfigConflictException> {
            define.rateLimiter(
                "rl-conflict",
                limitForPeriod = 20,
                limitRefreshPeriod = 60.seconds,
            )
        }
    }

    @Test fun `bulkhead throws on config conflict`() {
        define.bulkhead("bh-conflict", maxConcurrentCalls = 5)
        assertFailsWith<ConfigConflictException> {
            define.bulkhead("bh-conflict", maxConcurrentCalls = 10)
        }
    }
}
