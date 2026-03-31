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
package se.oyabun.tardigrade.redis

import org.redisson.api.RedissonClient
import se.oyabun.tardigrade.BulkheadProvider
import se.oyabun.tardigrade.CircuitBreakerProvider
import se.oyabun.tardigrade.Distribution
import se.oyabun.tardigrade.RateLimitProvider

object TardigradeRedis {
    fun distribution(redisson: RedissonClient): Distribution =
        RedisDistribution(redisson)

    fun circuitBreaker(redisson: RedissonClient): CircuitBreakerProvider =
        {
            name,
            failureRateThreshold,
            slidingWindowSize,
            minimumNumberOfCalls,
            waitDurationInOpenState,
            permittedCallsInHalfOpenState,
            ->
            RedisCircuitBreaker(
                name,
                redisson,
                failureRateThreshold,
                slidingWindowSize,
                minimumNumberOfCalls,
                waitDurationInOpenState,
                permittedCallsInHalfOpenState,
            )
        }

    fun rateLimiter(redisson: RedissonClient): RateLimitProvider =
        { name, limitForPeriod, limitRefreshPeriod, timeoutDuration ->
            RedisRateLimiter(
                name,
                redisson,
                limitForPeriod,
                limitRefreshPeriod,
                timeoutDuration,
            )
        }

    fun bulkhead(redisson: RedissonClient): BulkheadProvider =
        { name, maxConcurrentCalls, maxWaitDuration ->
            RedisBulkhead(name, redisson, maxConcurrentCalls, maxWaitDuration)
        }
}
