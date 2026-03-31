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
package se.oyabun.tardigrade.hazelcast

import com.hazelcast.core.HazelcastInstance
import se.oyabun.tardigrade.BulkheadProvider
import se.oyabun.tardigrade.CircuitBreakerProvider
import se.oyabun.tardigrade.Distribution
import se.oyabun.tardigrade.RateLimitProvider

object TardigradeHazelcast {
    fun distribution(hazelcast: HazelcastInstance): Distribution =
        HazelcastDistribution(hazelcast)

    fun circuitBreaker(hazelcast: HazelcastInstance): CircuitBreakerProvider =
        {
            name,
            failureRateThreshold,
            slidingWindowSize,
            minimumNumberOfCalls,
            waitDurationInOpenState,
            permittedCallsInHalfOpenState,
            ->
            HazelcastCircuitBreaker(
                name,
                hazelcast,
                failureRateThreshold,
                slidingWindowSize,
                minimumNumberOfCalls,
                waitDurationInOpenState,
                permittedCallsInHalfOpenState,
            )
        }

    fun rateLimiter(hazelcast: HazelcastInstance): RateLimitProvider =
        { name, limitForPeriod, limitRefreshPeriod, timeoutDuration ->
            HazelcastRateLimiter(
                name,
                hazelcast,
                limitForPeriod,
                limitRefreshPeriod,
                timeoutDuration,
            )
        }

    fun bulkhead(hazelcast: HazelcastInstance): BulkheadProvider =
        { name, maxConcurrentCalls, maxWaitDuration ->
            HazelcastBulkhead(
                name,
                hazelcast,
                maxConcurrentCalls,
                maxWaitDuration,
            )
        }
}
