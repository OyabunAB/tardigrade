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

import com.hazelcast.config.Config
import com.hazelcast.test.TestHazelcastInstanceFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import se.oyabun.tardigrade.hazelcast.bulkhead
import se.oyabun.tardigrade.hazelcast.circuitBreaker
import se.oyabun.tardigrade.hazelcast.rateLimiter
import kotlin.test.Test
import kotlin.test.assertSame

class HazelcastConcurrentInitTest {
    @AfterEach
    fun teardown() {
        Tardigrade.reset()
        factory.shutdownAll()
    }

    @Test
    fun `concurrent circuitBreaker initialization with same name succeeds`() {
        runBlocking {
            val hz = factory.newHazelcastInstance(
                Config().apply {
                    setProperty("hazelcast.logging.type", "slf4j")
                },
            )
            val results = mutableListOf<CircuitBreaker>()
            val jobs = (0..4).map {
                launch {
                    results.add(
                        Tardigrade.define.circuitBreaker(
                            "concurrent-cb",
                            hz,
                            failureRateThreshold = 50f,
                            minimumNumberOfCalls = 2,
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
    fun `concurrent rateLimiter initialization with same name succeeds`() {
        runBlocking {
            val hz = factory.newHazelcastInstance(
                Config().apply {
                    setProperty("hazelcast.logging.type", "slf4j")
                },
            )
            val results = mutableListOf<RateLimiter>()
            val jobs = (0..4).map {
                launch {
                    results.add(
                        Tardigrade.define.rateLimiter(
                            "concurrent-rl",
                            hz,
                            limitForPeriod = 100,
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
    fun `concurrent bulkhead initialization with same name succeeds`() {
        runBlocking {
            val hz = factory.newHazelcastInstance(
                Config().apply {
                    setProperty("hazelcast.logging.type", "slf4j")
                },
            )
            val results = mutableListOf<Bulkhead>()
            val jobs = (0..4).map {
                launch {
                    results.add(
                        Tardigrade.define.bulkhead(
                            "concurrent-bh",
                            hz,
                            maxConcurrentCalls = 10,
                        ),
                    )
                }
            }
            jobs.forEach { it.join() }
            val first = results[0]
            results.forEach { assertSame(first, it) }
        }
    }

    companion object {
        private val factory = TestHazelcastInstanceFactory(10)
    }
}
