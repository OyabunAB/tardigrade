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

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import se.oyabun.tardigrade.BulkheadOutcome
import se.oyabun.tardigrade.CircuitBreakerOutcome
import se.oyabun.tardigrade.InMemoryBulkhead
import se.oyabun.tardigrade.InMemoryCircuitBreaker
import se.oyabun.tardigrade.InMemoryRateLimiter
import se.oyabun.tardigrade.InMemoryRetry
import se.oyabun.tardigrade.RateLimiterOutcome
import se.oyabun.tardigrade.RetryExhaustedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FlowExtensionTest {
    @Test
    fun `withCircuitBreaker passes values through when closed`() =
        runBlocking {
            val cb = InMemoryCircuitBreaker("flow-cb-1")
            val result = flowOf(1, 2, 3).withCircuitBreaker(cb).toList()
            assertEquals(
                listOf(
                    CircuitBreakerOutcome.Ok(1),
                    CircuitBreakerOutcome.Ok(2),
                    CircuitBreakerOutcome.Ok(3),
                ),
                result,
            )
        }

    @Test
    fun `withCircuitBreaker yields Open when open`() =
        runBlocking {
            val cb =
                InMemoryCircuitBreaker(
                    "flow-cb-2",
                    failureRateThreshold = 50f,
                    minimumNumberOfCalls = 2,
                    slidingWindowSize = 2,
                )
            repeat(
                2,
            ) { runCatching { cb.execute { throw RuntimeException("trip") } } }

            val result = flowOf("value").withCircuitBreaker(cb).toList()
            assertEquals(listOf(CircuitBreakerOutcome.Open), result)
        }

    @Test
    fun `withRateLimiter passes values within limit`() =
        runBlocking {
            val rl =
                InMemoryRateLimiter(
                    "flow-rl-1",
                    limitForPeriod = 5,
                    limitRefreshPeriod = 60.seconds,
                )
            val result = flowOf(1, 2, 3).withRateLimiter(rl).toList()
            assertEquals(
                listOf(
                    RateLimiterOutcome.Ok(1),
                    RateLimiterOutcome.Ok(2),
                    RateLimiterOutcome.Ok(3),
                ),
                result,
            )
        }

    @Test
    fun `withRateLimiter yields Exceeded when limit exceeded`() =
        runBlocking {
            val rl =
                InMemoryRateLimiter(
                    "flow-rl-2",
                    limitForPeriod = 2,
                    limitRefreshPeriod = 60.seconds,
                )
            flowOf(1, 2).withRateLimiter(rl).toList()

            val result = flowOf(3).withRateLimiter(rl).toList()
            assertEquals(listOf(RateLimiterOutcome.Exceeded), result)
        }

    @Test
    fun `withBulkhead passes values through`() =
        runBlocking {
            val bh = InMemoryBulkhead("flow-bh-1", maxConcurrentCalls = 5)
            val result = flowOf(1, 2, 3).withBulkhead(bh).toList()
            assertEquals(
                listOf(
                    BulkheadOutcome.Ok(1),
                    BulkheadOutcome.Ok(2),
                    BulkheadOutcome.Ok(3),
                ),
                result,
            )
        }

    @Test
    fun `withBulkhead yields Full when full`() =
        runBlocking {
            val bh = InMemoryBulkhead("flow-bh-2", maxConcurrentCalls = 0)
            val result = flowOf("value").withBulkhead(bh).toList()
            assertEquals(listOf(BulkheadOutcome.Full), result)
        }

    @Test
    fun `withRetry retries on failure and eventually succeeds`() =
        runBlocking {
            var attempts = 0
            val result =
                flow {
                    attempts++
                    if (attempts < 3) throw RuntimeException("not yet")
                    emit("success")
                }.withRetry(
                    InMemoryRetry(
                        "flow-retry-1",
                        maxAttempts = 3,
                        waitDuration = 10.milliseconds,
                    ),
                ).toList()

            assertEquals(listOf("success"), result)
            assertEquals(3, attempts)
        }

    @Test
    fun `withRetry propagates error after exhausting attempts`() {
        runBlocking {
            assertFailsWith<RetryExhaustedException> {
                flow<String> { throw RuntimeException("always fails") }
                    .withRetry(
                        InMemoryRetry(
                            "flow-retry-2",
                            maxAttempts = 3,
                            waitDuration = 10.milliseconds,
                        ),
                    ).toList()
            }
        }
    }
}
