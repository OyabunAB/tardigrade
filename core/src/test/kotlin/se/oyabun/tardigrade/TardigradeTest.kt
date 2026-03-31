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

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class TardigradeTest {
    @AfterEach
    fun tearDown() = Tardigrade.reset()

    @Test
    fun `circuitBreaker returns same instance for same config`() {
        val a =
            Tardigrade.define.circuitBreaker(
                "cb",
                failureRateThreshold = 50f,
                minimumNumberOfCalls = 5,
            )
        val b =
            Tardigrade.define.circuitBreaker(
                "cb",
                failureRateThreshold = 50f,
                minimumNumberOfCalls = 5,
            )
        assertSame(a, b)
    }

    @Test
    fun `circuitBreaker throws on config conflict`() {
        Tardigrade.define.circuitBreaker(
            "cb",
            failureRateThreshold = 50f,
            minimumNumberOfCalls = 5,
        )
        assertFailsWith<ConfigConflictException> {
            Tardigrade.define.circuitBreaker(
                "cb",
                failureRateThreshold = 80f,
                minimumNumberOfCalls = 5,
            )
        }
    }

    @Test
    fun `rateLimiter returns same instance for same config`() {
        val a =
            Tardigrade.define.rateLimiter(
                "rl",
                limitForPeriod = 10,
                limitRefreshPeriod = 60.seconds,
            )
        val b =
            Tardigrade.define.rateLimiter(
                "rl",
                limitForPeriod = 10,
                limitRefreshPeriod = 60.seconds,
            )
        assertSame(a, b)
    }

    @Test
    fun `rateLimiter throws on config conflict`() {
        Tardigrade.define.rateLimiter(
            "rl",
            limitForPeriod = 10,
            limitRefreshPeriod = 60.seconds,
        )
        assertFailsWith<ConfigConflictException> {
            Tardigrade.define.rateLimiter(
                "rl",
                limitForPeriod = 20,
                limitRefreshPeriod = 60.seconds,
            )
        }
    }

    @Test
    fun `rateLimiter allows different timeoutDuration for same name`() {
        val a =
            Tardigrade.define.rateLimiter(
                "rl",
                limitForPeriod = 10,
                limitRefreshPeriod = 60.seconds,
                timeoutDuration = 1.seconds,
            )
        val b =
            Tardigrade.define.rateLimiter(
                "rl",
                limitForPeriod = 10,
                limitRefreshPeriod = 60.seconds,
                timeoutDuration = 5.seconds,
            )
        assertSame(a, b)
    }

    @Test
    fun `bulkhead returns same instance for same config`() {
        val a = Tardigrade.define.bulkhead("bh", maxConcurrentCalls = 5)
        val b = Tardigrade.define.bulkhead("bh", maxConcurrentCalls = 5)
        assertSame(a, b)
    }

    @Test
    fun `bulkhead throws on config conflict`() {
        Tardigrade.define.bulkhead("bh", maxConcurrentCalls = 5)
        assertFailsWith<ConfigConflictException> {
            Tardigrade.define.bulkhead("bh", maxConcurrentCalls = 10)
        }
    }

    @Test
    fun `bulkhead allows different maxWaitDuration for same name`() {
        val a =
            Tardigrade.define.bulkhead(
                "bh",
                maxConcurrentCalls = 5,
                maxWaitDuration = 1.seconds,
            )
        val b =
            Tardigrade.define.bulkhead(
                "bh",
                maxConcurrentCalls = 5,
                maxWaitDuration = 5.seconds,
            )
        assertSame(a, b)
    }

    @Test
    fun `reset clears cached instances for re-registration with diff conf`() {
        Tardigrade.define.circuitBreaker("cb", failureRateThreshold = 50f)
        Tardigrade.reset()
        Tardigrade.define.circuitBreaker("cb", failureRateThreshold = 80f)
    }

    @Test
    fun `concurrent circuitBreaker definition with same config succeeds`() {
        runBlocking {
            val results = mutableListOf<CircuitBreaker>()
            val jobs = (0..9).map {
                launch {
                    results.add(
                        Tardigrade.define.circuitBreaker(
                            "concurrent-cb",
                            failureRateThreshold = 50f,
                            minimumNumberOfCalls = 5,
                        ),
                    )
                }
            }
            jobs.forEach { it.join() }
            val first = results[0]
            results.forEach { assertSame(first, it) }
        }
    }

    @Test
    fun `concurrent circuitBreaker definition with different configs throws`() {
        runBlocking {
            val exceptions = mutableListOf<Throwable>()
            val jobs = (0..4).map { i ->
                launch {
                    try {
                        Tardigrade.define.circuitBreaker(
                            "conflict-cb",
                            failureRateThreshold = (50 + i * 10).toFloat(),
                            minimumNumberOfCalls = 5,
                        )
                    } catch (e: ConfigConflictException) {
                        exceptions.add(e)
                    }
                }
            }
            jobs.forEach { it.join() }
            kotlin.test.assertTrue(exceptions.isNotEmpty())
        }
    }

    @Test
    fun `circuitBreaker with scaling algorithm and 1 instance uses unscaled values`() {
        val cb = Tardigrade.define.circuitBreaker(
            "scale-1",
            minimumNumberOfCalls = 10,
            slidingWindowSize = 20,
            algorithm = DistributionAlgorithm.EvenlyDivided,
        )
        kotlin.test.assertEquals(10, cb.minimumNumberOfCalls)
        kotlin.test.assertEquals(20, cb.slidingWindowSize)
    }

    @Test
    fun `rateLimiter with scaling algorithm and 1 instance uses unscaled values`() {
        val rl = Tardigrade.define.rateLimiter(
            "scale-1",
            limitForPeriod = 100,
            algorithm = DistributionAlgorithm.EvenlyDivided,
        )
        kotlin.test.assertEquals(100, rl.limitForPeriod)
    }

    @Test
    fun `bulkhead with scaling algorithm and 1 instance uses unscaled values`() {
        val bh = Tardigrade.define.bulkhead(
            "scale-1",
            maxConcurrentCalls = 50,
            algorithm = DistributionAlgorithm.EvenlyDivided,
        )
        kotlin.test.assertEquals(50, bh.maxConcurrentCalls)
    }
}
