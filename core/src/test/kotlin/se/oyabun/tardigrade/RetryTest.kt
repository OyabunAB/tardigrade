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

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

class RetryTest {
    @AfterEach
    fun teardown() = Tardigrade.reset()

    @Test
    fun `succeeds on eventual success`() =
        runBlocking {
            val retry =
                Tardigrade.define.retry(
                    "r-success",
                    maxAttempts = 3,
                    waitDuration = 10.milliseconds,
                )
            var attempts = 0
            val outcome =
                retry.execute {
                    attempts++
                    if (attempts < 3) throw RuntimeException("not yet")
                    "success"
                }
            assertEquals(RetryOutcome.Ok("success"), outcome)
            assertEquals(3, attempts)
        }

    @Test
    fun `exhausts attempts and returns Exhausted`() =
        runBlocking {
            val retry =
                Tardigrade.define.retry(
                    "r-exhaust",
                    maxAttempts = 3,
                    waitDuration = 10.milliseconds,
                )
            var attempts = 0
            val outcome =
                retry.execute {
                    attempts++
                    throw RuntimeException("always fails")
                }
            assertIs<RetryOutcome.Exhausted>(outcome)
            assertEquals(3, attempts)
        }

    @Test
    fun `does not retry on excluded exception type`() =
        runBlocking {
            val retry =
                Tardigrade.define.retry(
                    name = "r-no-retry",
                    maxAttempts = 3,
                    waitDuration = 10.milliseconds,
                    retryOn = setOf(IllegalStateException::class),
                )
            var attempts = 0
            assertFailsWith<IllegalArgumentException> {
                retry.execute {
                    attempts++
                    throw IllegalArgumentException("not retried")
                }
            }
            assertEquals(1, attempts)
        }

    @Test
    fun `applies exponential backoff`() =
        runBlocking {
            val initialWaitMs = 50L
            val counter = AtomicInteger(0)
            val retry =
                Tardigrade.define.retry(
                    name = "r-backoff",
                    maxAttempts = 3,
                    waitDuration = initialWaitMs.milliseconds,
                    exponentialBackoffMultiplier = 2.0,
                )
            val elapsed =
                measureTime {
                    retry.execute {
                        counter.incrementAndGet()
                        throw RuntimeException("fail")
                    }
                }
            val expectedMinimum =
                (initialWaitMs + initialWaitMs * 2)
                    .milliseconds
            assertTrue(
                elapsed >= expectedMinimum - 10.milliseconds,
                "Expected >= $expectedMinimum elapsed, got $elapsed",
            )
            assertEquals(3, counter.get())
        }

    @Test
    fun `throws on config conflict`() {
        Tardigrade.define.retry("r-conflict", maxAttempts = 3)
        assertFailsWith<ConfigConflictException> {
            Tardigrade.define.retry("r-conflict", maxAttempts = 5)
        }
    }

    @Test
    fun `recall by name returns same instance`() {
        val r1 = Tardigrade.define.retry("r-recall")
        val r2 = Tardigrade.retry["r-recall"]
        assertEquals(r1, r2)
    }

    @Test
    fun `lookup throws when not defined`() {
        assertFailsWith<IllegalStateException> {
            Tardigrade.retry["r-unknown"]
        }
    }
}
