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
import se.oyabun.tardigrade.Bulkhead
import se.oyabun.tardigrade.CircuitBreaker
import se.oyabun.tardigrade.DistributionAlgorithm
import se.oyabun.tardigrade.RateLimiter
import se.oyabun.tardigrade.Tardigrade
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun Tardigrade.define.circuitBreaker(
    name: String,
    redisson: RedissonClient,
    failureRateThreshold: Float = 50f,
    slidingWindowSize: Int = 100,
    minimumNumberOfCalls: Int = 10,
    waitDurationInOpenState: Duration = 60.seconds,
    permittedCallsInHalfOpenState: Int = 5,
    algorithm: DistributionAlgorithm = DistributionAlgorithm.AsIs,
): CircuitBreaker =
    circuitBreaker(
        name,
        failureRateThreshold,
        slidingWindowSize,
        minimumNumberOfCalls,
        waitDurationInOpenState,
        permittedCallsInHalfOpenState,
        algorithm,
        RedisDistribution(redisson),
    )

fun Tardigrade.define.rateLimiter(
    name: String,
    redisson: RedissonClient,
    limitForPeriod: Long = 50,
    limitRefreshPeriod: Duration = 1.seconds,
    timeoutDuration: Duration = Duration.ZERO,
    algorithm: DistributionAlgorithm = DistributionAlgorithm.AsIs,
): RateLimiter =
    rateLimiter(
        name,
        limitForPeriod,
        limitRefreshPeriod,
        timeoutDuration,
        algorithm,
        RedisDistribution(redisson),
    )

fun Tardigrade.define.bulkhead(
    name: String,
    redisson: RedissonClient,
    maxConcurrentCalls: Int = 25,
    maxWaitDuration: Duration = Duration.ZERO,
    algorithm: DistributionAlgorithm = DistributionAlgorithm.AsIs,
): Bulkhead =
    bulkhead(
        name,
        maxConcurrentCalls,
        maxWaitDuration,
        algorithm,
        RedisDistribution(redisson),
    )
