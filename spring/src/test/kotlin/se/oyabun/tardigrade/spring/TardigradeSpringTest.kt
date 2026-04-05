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
package se.oyabun.tardigrade.spring

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import se.oyabun.tardigrade.ConfigConflictException
import se.oyabun.tardigrade.Tardigrade
import se.oyabun.tardigrade.spring.annotation.WithCircuitBreaker
import se.oyabun.tardigrade.spring.annotation.WithRateLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class TardigradeSpringTest {
    @Configuration
    @Import(TardigradeAutoConfiguration::class)
    open class AppConfig {
        @Bean open fun service() = Service()
    }

    open class Service {
        @WithCircuitBreaker("shared-cb")
        open fun failingCall(): String = error("failure")

        @WithCircuitBreaker("shared-cb")
        open fun successCall(): String = "ok"

        @WithRateLimiter("shared-rl")
        open fun rlCall(): String = "ok"
    }

    private lateinit var ctx1: AnnotationConfigApplicationContext
    private lateinit var ctx2: AnnotationConfigApplicationContext

    @BeforeEach
    fun setup() {
        ctx1 = AnnotationConfigApplicationContext(AppConfig::class.java)
        ctx2 = AnnotationConfigApplicationContext(AppConfig::class.java)
    }

    @AfterEach
    fun teardown() {
        ctx1.close()
        ctx2.close()
        Tardigrade.reset()
    }

    @Test
    fun `circuit breaker opened by app1 is seen as open by app2`() {
        val service1 = ctx1.getBean(Service::class.java)
        val service2 = ctx2.getBean(Service::class.java)
        Tardigrade.define.circuitBreaker(
            "shared-cb",
            failureRateThreshold = 50f,
            minimumNumberOfCalls = 2,
            slidingWindowSize = 2,
        )
        repeat(
            2,
        ) { assertFailsWith<RuntimeException> { service1.failingCall() } }
        val ex = assertFailsWith<ResponseStatusException> { service2.successCall() }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.statusCode)
    }

    @Test
    fun `rate limiter exhausted by app1 denies app2`() {
        val service1 = ctx1.getBean(Service::class.java)
        val service2 = ctx2.getBean(Service::class.java)
        Tardigrade.define.rateLimiter(
            "shared-rl",
            limitForPeriod = 1,
            limitRefreshPeriod = 60.seconds,
        )
        assertEquals("ok", service1.rlCall())
        val ex = assertFailsWith<ResponseStatusException> { service2.rlCall() }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.statusCode)
    }

    @Test
    fun `conflicting circuit breaker config across apps throws`() {
        Tardigrade.define.circuitBreaker(
            "shared-cb",
            failureRateThreshold = 50f,
            minimumNumberOfCalls = 2,
            slidingWindowSize = 2,
        )
        assertFailsWith<ConfigConflictException> {
            Tardigrade.define.circuitBreaker(
                "shared-cb",
                failureRateThreshold = 80f,
                minimumNumberOfCalls = 2,
                slidingWindowSize = 2,
            )
        }
    }

    @Test
    fun `conflicting rate limiter config across apps throws`() {
        Tardigrade.define.rateLimiter(
            "shared-rl",
            limitForPeriod = 10,
            limitRefreshPeriod = 60.seconds,
        )
        assertFailsWith<ConfigConflictException> {
            Tardigrade.define.rateLimiter(
                "shared-rl",
                limitForPeriod = 20,
                limitRefreshPeriod = 60.seconds,
            )
        }
    }
}
