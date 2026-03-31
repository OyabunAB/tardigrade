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
import org.junit.jupiter.api.BeforeEach
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import se.oyabun.tardigrade.redis.bulkhead
import se.oyabun.tardigrade.redis.circuitBreaker
import se.oyabun.tardigrade.redis.rateLimiter
import kotlin.test.Test
import kotlin.test.assertSame

@Testcontainers(disabledWithoutDocker = true)
class RedisConcurrentInitTest {
    private lateinit var redisson: RedissonClient

    @BeforeEach
    fun setup() {
        redisson =
            Redisson.create(
                org.redisson.config.Config().apply {
                    useSingleServer().address =
                        "redis://${redis.host}:${redis.getMappedPort(6379)}"
                },
            )
    }

    @AfterEach
    fun teardown() {
        Tardigrade.reset()
        redisson.keys.flushall()
        redisson.shutdown()
    }

    @Test
    fun `concurrent circuitBreaker initialization with same name succeeds`() {
        runBlocking {
            val results = mutableListOf<CircuitBreaker>()
            val jobs = (0..4).map {
                launch {
                    results.add(
                        Tardigrade.define.circuitBreaker(
                            "concurrent-cb",
                            redisson,
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
            val results = mutableListOf<RateLimiter>()
            val jobs = (0..4).map {
                launch {
                    results.add(
                        Tardigrade.define.rateLimiter(
                            "concurrent-rl",
                            redisson,
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
            val results = mutableListOf<Bulkhead>()
            val jobs = (0..4).map {
                launch {
                    results.add(
                        Tardigrade.define.bulkhead(
                            "concurrent-bh",
                            redisson,
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
        @Container @JvmField
        val redis =
            GenericContainer(
                DockerImageName.parse("redis:7-alpine"),
            ).apply {
                withExposedPorts(6379)
            }
    }
}
