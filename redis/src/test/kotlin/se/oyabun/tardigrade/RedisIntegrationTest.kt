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

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import se.oyabun.tardigrade.Tardigrade.define
import se.oyabun.tardigrade.redis.bulkhead
import se.oyabun.tardigrade.redis.circuitBreaker
import se.oyabun.tardigrade.redis.rateLimiter

@Testcontainers(disabledWithoutDocker = true)
class RedisIntegrationTest : TardigradeContractTest() {
    private lateinit var redisson: RedissonClient

    override fun setup() {
        redisson =
            Redisson.create(
                Config().apply {
                    useSingleServer().address =
                        "redis://${redis.host}:${redis.getMappedPort(6379)}"
                },
            )
    }

    override fun initComponents(
        circuitBreakers: List<CircuitBreakerDefinition>,
        rateLimiters: List<RateLimiterDefinition>,
        bulkheads: List<BulkheadDefinition>,
    ) {
        circuitBreakers.forEach {
            define.circuitBreaker(
                it.name,
                redisson,
                it.failureRateThreshold,
                it.slidingWindowSize,
                it.minimumNumberOfCalls,
                it.waitDurationInOpenState,
                it.permittedCallsInHalfOpenState,
            )
        }
        rateLimiters.forEach {
            define.rateLimiter(
                it.name,
                redisson,
                it.limitForPeriod,
                it.limitRefreshPeriod,
                it.timeoutDuration,
            )
        }
        bulkheads.forEach {
            define.bulkhead(
                it.name,
                redisson,
                it.maxConcurrentCalls,
                it.maxWaitDuration,
            )
        }
    }

    override fun teardown() {
        Tardigrade.reset()
        redisson.keys.flushall()
        redisson.shutdown()
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
