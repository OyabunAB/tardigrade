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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import se.oyabun.tardigrade.hazelcast.bulkhead
import se.oyabun.tardigrade.hazelcast.circuitBreaker
import se.oyabun.tardigrade.hazelcast.rateLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class HazelcastEvenlyDividedTest {
    @AfterEach
    fun teardown() {
        Tardigrade.reset()
        factory.shutdownAll()
    }

    private fun twoMemberConfig() =
        Config().apply {
            setProperty("hazelcast.logging.type", "slf4j")
        }

    @Test
    fun `rate limiter limit is divided evenly across 2 instances`() =
        runBlocking {
            val hz1 = factory.newHazelcastInstance(twoMemberConfig())
            factory.newHazelcastInstance(twoMemberConfig())
            val rl =
                Tardigrade.define.rateLimiter(
                    "hz-rl",
                    hz1,
                    limitForPeriod = 4,
                    limitRefreshPeriod = 60.seconds,
                    algorithm = DistributionAlgorithm.EvenlyDivided,
                )
            assertEquals(2L, rl.limitForPeriod)
        }

    @Test
    fun `bulkhead capacity is divided evenly across 2 instances`() =
        runBlocking {
            val hz1 = factory.newHazelcastInstance(twoMemberConfig())
            factory.newHazelcastInstance(twoMemberConfig())
            val bh =
                Tardigrade.define.bulkhead(
                    "hz-bh",
                    hz1,
                    maxConcurrentCalls = 10,
                    algorithm = DistributionAlgorithm.EvenlyDivided,
                )
            assertEquals(5, bh.maxConcurrentCalls)
        }

    @Test
    fun `circuit breaker windows are divided evenly across 2 instances`() =
        runBlocking {
            val hz1 = factory.newHazelcastInstance(twoMemberConfig())
            factory.newHazelcastInstance(twoMemberConfig())
            val cb =
                Tardigrade.define.circuitBreaker(
                    name = "hz-cb",
                    hazelcast = hz1,
                    minimumNumberOfCalls = 10,
                    slidingWindowSize = 20,
                    algorithm = DistributionAlgorithm.EvenlyDivided,
                )
            assertEquals(5, cb.minimumNumberOfCalls)
            assertEquals(10, cb.slidingWindowSize)
        }

    companion object {
        private val factory = TestHazelcastInstanceFactory(10)
    }
}
