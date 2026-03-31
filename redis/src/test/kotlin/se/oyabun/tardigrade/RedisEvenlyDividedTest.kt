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
import org.junit.jupiter.api.BeforeEach
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import se.oyabun.tardigrade.redis.TardigradeRedis
import se.oyabun.tardigrade.redis.bulkhead
import se.oyabun.tardigrade.redis.rateLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@Testcontainers(disabledWithoutDocker = true)
class RedisEvenlyDividedTest {
    private lateinit var redisson: RedissonClient

    private fun redissonConfig() =
        org.redisson.config.Config().apply {
            useSingleServer().address =
                "redis://${redis.host}:${redis.getMappedPort(6379)}"
        }

    @BeforeEach
    fun setup() {
        redisson = Redisson.create(redissonConfig())
    }

    @AfterEach
    fun teardown() {
        Tardigrade.reset()
        redisson.keys.flushall()
        redisson.shutdown()
    }

    @Test
    fun `rate limiter limit is divided evenly across 2 registered instances`() =
        runBlocking {
            val redisson2 = Redisson.create(redissonConfig())
            val dist2 = TardigradeRedis.distribution(redisson2)
            dist2.register()
            try {
                val rl =
                    Tardigrade.define.rateLimiter(
                        "redis-rl",
                        redisson,
                        limitForPeriod = 4,
                        limitRefreshPeriod = 60.seconds,
                        algorithm = DistributionAlgorithm.EvenlyDivided,
                    )
                assertEquals(2L, rl.limitForPeriod)
            } finally {
                dist2.deregister()
                redisson2.shutdown()
            }
        }

    @Test
    fun `bulkhead capacity is divided evenly across 2 registered instances`() =
        runBlocking {
            val redisson2 = Redisson.create(redissonConfig())
            val dist2 = TardigradeRedis.distribution(redisson2)
            dist2.register()
            try {
                val bh =
                    Tardigrade.define.bulkhead(
                        "redis-bh",
                        redisson,
                        maxConcurrentCalls = 10,
                        algorithm = DistributionAlgorithm.EvenlyDivided,
                    )
                assertEquals(5, bh.maxConcurrentCalls)
            } finally {
                dist2.deregister()
                redisson2.shutdown()
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
