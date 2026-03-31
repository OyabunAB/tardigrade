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
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.test.TestHazelcastInstanceFactory
import se.oyabun.tardigrade.Tardigrade.define
import se.oyabun.tardigrade.hazelcast.bulkhead
import se.oyabun.tardigrade.hazelcast.circuitBreaker
import se.oyabun.tardigrade.hazelcast.rateLimiter

class HazelcastIntegrationTest : TardigradeContractTest() {
    private lateinit var hazelcast: HazelcastInstance

    override fun setup() {
        hazelcast =
            factory.newHazelcastInstance(
                Config().apply {
                    setProperty("hazelcast.logging.type", "slf4j")
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
                hazelcast,
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
                hazelcast,
                it.limitForPeriod,
                it.limitRefreshPeriod,
                it.timeoutDuration,
            )
        }
        bulkheads.forEach {
            define.bulkhead(
                it.name,
                hazelcast,
                it.maxConcurrentCalls,
                it.maxWaitDuration,
            )
        }
    }

    override fun teardown() {
        Tardigrade.reset()
        hazelcast.shutdown()
    }

    companion object {
        private val factory = TestHazelcastInstanceFactory(10)
    }
}
