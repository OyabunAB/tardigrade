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
package se.oyabun.tardigrade.extension

import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import se.oyabun.tardigrade.BulkheadOutcome
import se.oyabun.tardigrade.CircuitBreakerOutcome
import se.oyabun.tardigrade.InMemoryBulkhead
import se.oyabun.tardigrade.InMemoryCircuitBreaker
import se.oyabun.tardigrade.InMemoryRateLimiter
import se.oyabun.tardigrade.InMemoryRetry
import se.oyabun.tardigrade.RateLimiterOutcome
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import reactor.core.Exceptions as ReactorExceptions

class ReactorExtensionTest {
    private fun openCircuitBreaker(name: String) =
        InMemoryCircuitBreaker(
            name,
            failureRateThreshold = 50f,
            minimumNumberOfCalls = 2,
            slidingWindowSize = 2,
        ).also { cb ->
            runBlocking {
                repeat(
                    2,
                ) {
                    runCatching {
                        cb.execute { error("trip") }
                    }
                }
            }
        }

    @Test
    fun `reactor mono withCircuitBreaker passes value when closed`() {
        val cb = InMemoryCircuitBreaker("reactor-mono-cb-1")
        StepVerifier
            .create(Mono.just("ok").withCircuitBreaker(cb))
            .expectNextMatches {
                it is CircuitBreakerOutcome.Ok<*> &&
                    it.value == "ok"
            }.verifyComplete()
    }

    @Test
    fun `reactor mono withCircuitBreaker yields Open when open`() {
        StepVerifier
            .create(
                Mono
                    .just(
                        "ok",
                    ).withCircuitBreaker(
                        openCircuitBreaker("reactor-mono-cb-2"),
                    ),
            ).expectNext(CircuitBreakerOutcome.Open)
            .verifyComplete()
    }

    @Test
    fun `reactor mono withRateLimiter passes value within limit`() {
        val rl =
            InMemoryRateLimiter(
                "reactor-mono-rl-1",
                limitForPeriod = 5,
                limitRefreshPeriod = 60.seconds,
            )
        StepVerifier
            .create(Mono.just("ok").withRateLimiter(rl))
            .expectNextMatches {
                it is RateLimiterOutcome.Ok<*> &&
                    it.value == "ok"
            }.verifyComplete()
    }

    @Test
    fun `reactor mono withRateLimiter yields Exceeded when limit exceeded`() {
        val rl =
            InMemoryRateLimiter(
                "reactor-mono-rl-2",
                limitForPeriod = 1,
                limitRefreshPeriod = 60.seconds,
            )
        Mono.just("first").withRateLimiter(rl).block()

        StepVerifier
            .create(Mono.just("over").withRateLimiter(rl))
            .expectNext(RateLimiterOutcome.Exceeded)
            .verifyComplete()
    }

    @Test
    fun `reactor mono withBulkhead passes value`() {
        val bh = InMemoryBulkhead("reactor-mono-bh-1", maxConcurrentCalls = 1)
        StepVerifier
            .create(Mono.just("ok").withBulkhead(bh))
            .expectNextMatches {
                it is BulkheadOutcome.Ok<*> && it.value == "ok"
            }.verifyComplete()
    }

    @Test
    fun `reactor mono withBulkhead yields Full when full`() {
        val bh = InMemoryBulkhead("reactor-mono-bh-2", maxConcurrentCalls = 1)
        val slow =
            mono {
                bh.execute {
                    delay(300.milliseconds)
                    "slow"
                }
            }
        val overflow =
            mono {
                delay(50.milliseconds)
                bh.execute { "fast" }
            }

        StepVerifier
            .create(Mono.zip(slow, overflow))
            .expectNextMatches {
                it.t1 is BulkheadOutcome.Ok<*> &&
                    it.t2 == BulkheadOutcome.Full
            }.verifyComplete()
    }

    @Test
    fun `reactor mono withRetry retries and eventually succeeds`() {
        val counter = AtomicInteger(0)
        val mono =
            Mono.defer {
                if (counter.incrementAndGet() <
                    3
                ) {
                    Mono.error(RuntimeException("not yet"))
                } else {
                    Mono.just("ok")
                }
            }
        StepVerifier
            .create(
                mono.withRetry(
                    InMemoryRetry(
                        "reactor-mono-retry-1",
                        maxAttempts = 3,
                        waitDuration = 10.milliseconds,
                    ),
                ),
            ).expectNext("ok")
            .verifyComplete()
    }

    @Test
    fun `reactor mono withRetry propagates after exhausting attempts`() {
        StepVerifier
            .create(
                Mono
                    .error<String>(RuntimeException("always fails"))
                    .withRetry(
                        InMemoryRetry(
                            "reactor-mono-retry-2",
                            maxAttempts = 2,
                            waitDuration = 10.milliseconds,
                        ),
                    ),
            ).expectErrorSatisfies { e ->
                assertTrue(ReactorExceptions.isRetryExhausted(e))
                assertTrue(e.cause is RuntimeException)
            }.verify()
    }

    @Test
    fun `reactor flux withCircuitBreaker passes values when closed`() {
        val cb = InMemoryCircuitBreaker("reactor-flux-cb-1")
        StepVerifier
            .create(Flux.just("a", "b", "c").withCircuitBreaker(cb))
            .expectNextMatches {
                it is CircuitBreakerOutcome.Ok<*> &&
                    it.value == "a"
            }.expectNextMatches {
                it is CircuitBreakerOutcome.Ok<*> &&
                    it.value == "b"
            }.expectNextMatches {
                it is CircuitBreakerOutcome.Ok<*> &&
                    it.value == "c"
            }.verifyComplete()
    }

    @Test
    fun `reactor flux withCircuitBreaker yields for all items when open`() {
        StepVerifier
            .create(
                Flux
                    .just(
                        "a",
                        "b",
                    ).withCircuitBreaker(
                        openCircuitBreaker("reactor-flux-cb-2"),
                    ),
            ).expectNext(CircuitBreakerOutcome.Open, CircuitBreakerOutcome.Open)
            .verifyComplete()
    }

    @Test
    fun `reactor flux withRateLimiter passes values within limit`() {
        val rl =
            InMemoryRateLimiter(
                "reactor-flux-rl-1",
                limitForPeriod = 5,
                limitRefreshPeriod = 60.seconds,
            )
        StepVerifier
            .create(Flux.just("a", "b", "c").withRateLimiter(rl))
            .expectNextMatches {
                it is RateLimiterOutcome.Ok<*> &&
                    it.value == "a"
            }.expectNextMatches {
                it is RateLimiterOutcome.Ok<*> &&
                    it.value == "b"
            }.expectNextMatches {
                it is RateLimiterOutcome.Ok<*> &&
                    it.value == "c"
            }.verifyComplete()
    }

    @Test
    fun `reactor flux withRateLimiter yields Exceeded when limit exceeded`() {
        val rl =
            InMemoryRateLimiter(
                "reactor-flux-rl-2",
                limitForPeriod = 2,
                limitRefreshPeriod = 60.seconds,
            )
        StepVerifier
            .create(Flux.just("a", "b", "c").withRateLimiter(rl))
            .expectNextMatches {
                it is RateLimiterOutcome.Ok<*> &&
                    it.value == "a"
            }.expectNextMatches {
                it is RateLimiterOutcome.Ok<*> &&
                    it.value == "b"
            }.expectNext(RateLimiterOutcome.Exceeded)
            .verifyComplete()
    }

    @Test
    fun `reactor flux withBulkhead passes values`() {
        val bh = InMemoryBulkhead("reactor-flux-bh-1", maxConcurrentCalls = 5)
        StepVerifier
            .create(Flux.just("a", "b", "c").withBulkhead(bh))
            .expectNextMatches {
                it is BulkheadOutcome.Ok<*> && it.value == "a"
            }.expectNextMatches {
                it is BulkheadOutcome.Ok<*> && it.value == "b"
            }.expectNextMatches {
                it is BulkheadOutcome.Ok<*> && it.value == "c"
            }.verifyComplete()
    }

    @Test
    fun `reactor flux withRetry retries and eventually succeeds`() {
        val counter = AtomicInteger(0)
        val flux =
            Flux.defer {
                if (counter.incrementAndGet() <
                    3
                ) {
                    Flux.error(RuntimeException("not yet"))
                } else {
                    Flux.just("a", "b")
                }
            }
        StepVerifier
            .create(
                flux.withRetry(
                    InMemoryRetry(
                        "reactor-flux-retry-1",
                        maxAttempts = 3,
                        waitDuration = 10.milliseconds,
                    ),
                ),
            ).expectNext("a", "b")
            .verifyComplete()
    }

    @Test
    fun `reactor flux withRetry propagates after exhausting attempts`() {
        StepVerifier
            .create(
                Flux
                    .error<String>(RuntimeException("always fails"))
                    .withRetry(
                        InMemoryRetry(
                            "reactor-flux-retry-2",
                            maxAttempts = 2,
                            waitDuration = 10.milliseconds,
                        ),
                    ),
            ).expectErrorSatisfies { e ->
                assertTrue(ReactorExceptions.isRetryExhausted(e))
                assertTrue(e.cause is RuntimeException)
            }.verify()
    }
}
