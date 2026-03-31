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
package se.oyabun.tardigrade.spring.aspect

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.http.HttpStatus
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import se.oyabun.tardigrade.Tardigrade
import se.oyabun.tardigrade.spring.annotation.WithBulkhead
import se.oyabun.tardigrade.spring.annotation.WithCircuitBreaker
import se.oyabun.tardigrade.spring.annotation.WithRateLimiter
import se.oyabun.tardigrade.spring.annotation.WithRetry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@SpringJUnitConfig(TardigradeAspectTest.Config::class)
class TardigradeAspectTest {
    @Configuration
    @EnableAspectJAutoProxy
    open class Config {
        @Bean open fun aspect() = TardigradeAspect()

        @Bean open fun target() = Target()
    }

    open class Target {
        private val _retryAttempts = AtomicInteger()
        open fun getRetryAttempts() = _retryAttempts

        @WithCircuitBreaker(CB)
        open fun cbBlock(): String = "ok"

        @WithCircuitBreaker(CB_OPEN)
        open fun cbOpenBlock(): String = "ok"

        @WithCircuitBreaker(CB)
        open fun cbMono(): Mono<String> = Mono.just("ok")

        @WithCircuitBreaker(CB_OPEN)
        open fun cbOpenMono(): Mono<String> = Mono.just("ok")

        @WithCircuitBreaker(CB)
        open fun cbFlux(): Flux<String> = Flux.just("a", "b")

        @WithCircuitBreaker(CB_OPEN)
        open fun cbOpenFlux(): Flux<String> = Flux.just("a", "b")

        @WithRateLimiter(RL)
        open fun rlBlock(): String = "ok"

        @WithRateLimiter(RL_EXCEEDED)
        open fun rlExceededBlock(): String = "ok"

        @WithRateLimiter(RL)
        open fun rlMono(): Mono<String> = Mono.just("ok")

        @WithRateLimiter(RL_EXCEEDED)
        open fun rlExceededMono(): Mono<String> = Mono.just("ok")

        @WithBulkhead(BH)
        open fun bhBlock(): String = "ok"

        @WithBulkhead(BH)
        open fun bhMono(): Mono<String> = Mono.just("ok")

        @WithRetry(RETRY)
        open fun retryBlock(): String {
            val n = _retryAttempts.incrementAndGet()
            if (n < 3) throw RuntimeException("not yet")
            return "ok"
        }

        @WithRetry(RETRY_FAIL)
        open fun retryFailBlock(): String =
            throw RuntimeException("always fails")

        @WithRetry(RETRY)
        open fun retryMono(): Mono<String> =
            Mono.defer {
                val n = _retryAttempts.incrementAndGet()
                if (n < 3) {
                    Mono.error(RuntimeException("not yet"))
                } else {
                    Mono.just("ok")
                }
            }

        @WithRetry(RETRY_FAIL)
        open fun retryFailMono(): Mono<String> =
            Mono.error(RuntimeException("always fails"))
    }

    @Autowired
    lateinit var target: Target

    @BeforeEach
    fun setup() {
        Tardigrade.define.circuitBreaker(CB)
        val openCb =
            Tardigrade.define.circuitBreaker(
                CB_OPEN,
                failureRateThreshold = 50f,
                minimumNumberOfCalls = 2,
                slidingWindowSize = 2,
            )
        runBlocking {
            repeat(
                2,
            ) { runCatching { openCb.execute { throw RuntimeException() } } }
        }

        Tardigrade.define.rateLimiter(
            RL,
            limitForPeriod = 5,
            limitRefreshPeriod = 60.seconds,
        )
        val exceededRl =
            Tardigrade.define.rateLimiter(
                RL_EXCEEDED,
                limitForPeriod = 1,
                limitRefreshPeriod = 60.seconds,
            )
        runBlocking { exceededRl.execute { "consume" } }

        Tardigrade.define.bulkhead(BH)

        Tardigrade.define.retry(
            RETRY,
            maxAttempts = 3,
            waitDuration = 10.milliseconds,
        )
        Tardigrade.define.retry(
            RETRY_FAIL,
            maxAttempts = 2,
            waitDuration = 10.milliseconds,
        )
    }

    @AfterEach
    fun teardown() {
        Tardigrade.reset()
        target.getRetryAttempts().set(0)
    }

    @Test
    fun `circuit breaker blocking passes value when closed`() {
        assertEquals("ok", target.cbBlock())
    }

    @Test
    fun `circuit breaker blocking responds 503 when open`() {
        val ex =
            assertFailsWith<ResponseStatusException> { target.cbOpenBlock() }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.statusCode)
    }

    @Test
    fun `circuit breaker mono passes value when closed`() {
        StepVerifier
            .create(target.cbMono())
            .expectNext("ok")
            .verifyComplete()
    }

    @Test
    fun `circuit breaker mono errors with 503 when open`() {
        StepVerifier
            .create(target.cbOpenMono())
            .expectErrorSatisfies { e ->
                assertTrue(e is ResponseStatusException)
                assertEquals(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    e.statusCode,
                )
            }.verify()
    }

    @Test
    fun `circuit breaker flux passes values when closed`() {
        StepVerifier
            .create(target.cbFlux())
            .expectNext("a", "b")
            .verifyComplete()
    }

    @Test
    fun `circuit breaker flux errors with 503 when open`() {
        StepVerifier
            .create(target.cbOpenFlux())
            .expectErrorSatisfies { e ->
                assertTrue(e is ResponseStatusException)
                assertEquals(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    e.statusCode,
                )
            }.verify()
    }

    @Test
    fun `rate limiter blocking passes value within limit`() {
        assertEquals("ok", target.rlBlock())
    }

    @Test
    fun `rate limiter blocking responds 429 when exceeded`() {
        val ex =
            assertFailsWith<ResponseStatusException> {
                target.rlExceededBlock()
            }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.statusCode)
    }

    @Test
    fun `rate limiter mono passes value within limit`() {
        StepVerifier
            .create(target.rlMono())
            .expectNext("ok")
            .verifyComplete()
    }

    @Test
    fun `rate limiter mono errors with 429 when exceeded`() {
        StepVerifier
            .create(target.rlExceededMono())
            .expectErrorSatisfies { e ->
                assertTrue(e is ResponseStatusException)
                assertEquals(
                    HttpStatus.TOO_MANY_REQUESTS,
                    e.statusCode,
                )
            }.verify()
    }

    @Test
    fun `bulkhead blocking passes value within capacity`() {
        assertEquals("ok", target.bhBlock())
    }

    @Test
    fun `bulkhead mono passes value within capacity`() {
        StepVerifier
            .create(target.bhMono())
            .expectNext("ok")
            .verifyComplete()
    }

    @Test
    fun `retry blocking retries until success`() {
        assertEquals("ok", target.retryBlock())
        assertEquals(3, target.getRetryAttempts().get())
    }

    @Test
    fun `retry blocking responds 503 when exhausted`() {
        val ex =
            assertFailsWith<ResponseStatusException> { target.retryFailBlock() }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.statusCode)
    }

    @Test
    fun `retry mono retries until success`() {
        StepVerifier
            .create(target.retryMono())
            .expectNext("ok")
            .verifyComplete()
    }

    @Test
    fun `retry mono errors with 503 when exhausted`() {
        StepVerifier
            .create(target.retryFailMono())
            .expectErrorSatisfies { e ->
                assertTrue(e is ResponseStatusException)
                assertEquals(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    e.statusCode,
                )
            }.verify()
    }

    companion object {
        private const val CB = "sp-cb"
        private const val CB_OPEN = "sp-cb-open"
        private const val RL = "sp-rl"
        private const val RL_EXCEEDED = "sp-rl-exceeded"
        private const val BH = "sp-bh"
        private const val RETRY = "sp-retry"
        private const val RETRY_FAIL = "sp-retry-fail"
    }
}
