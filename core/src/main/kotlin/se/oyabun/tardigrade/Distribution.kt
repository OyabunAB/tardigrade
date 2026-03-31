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

interface Distribution {
    val circuitBreakers: CircuitBreakerProvider
    val rateLimiters: RateLimitProvider
    val bulkheads: BulkheadProvider
    val retries: RetryProvider get() = ::InMemoryRetry

    fun instanceCount(): Int = 1

    fun register() {}

    fun deregister() {}
}

sealed interface DistributionAlgorithm {
    val requiresInstanceCount: Boolean get() = false

    fun scaleRateLimit(
        limitForPeriod: Long,
        instanceCount: Int,
    ): Long

    fun scaleBulkhead(
        maxConcurrentCalls: Int,
        instanceCount: Int,
    ): Int

    fun scaleCircuitBreaker(
        minimumNumberOfCalls: Int,
        slidingWindowSize: Int,
        instanceCount: Int,
    ): Pair<Int, Int>

    object AsIs : DistributionAlgorithm {
        override fun scaleRateLimit(
            limitForPeriod: Long,
            instanceCount: Int,
        ) = limitForPeriod

        override fun scaleBulkhead(
            maxConcurrentCalls: Int,
            instanceCount: Int,
        ) = maxConcurrentCalls

        override fun scaleCircuitBreaker(
            minimumNumberOfCalls: Int,
            slidingWindowSize: Int,
            instanceCount: Int,
        ) = minimumNumberOfCalls to slidingWindowSize
    }

    object EvenlyDivided : DistributionAlgorithm {
        override val requiresInstanceCount = true

        override fun scaleRateLimit(
            limitForPeriod: Long,
            instanceCount: Int,
        ) = maxOf(1L, limitForPeriod / instanceCount)

        override fun scaleBulkhead(
            maxConcurrentCalls: Int,
            instanceCount: Int,
        ) = maxOf(1, maxConcurrentCalls / instanceCount)

        override fun scaleCircuitBreaker(
            minimumNumberOfCalls: Int,
            slidingWindowSize: Int,
            instanceCount: Int,
        ) = maxOf(1, minimumNumberOfCalls / instanceCount) to
            maxOf(1, slidingWindowSize / instanceCount)
    }
}

object InMemoryDistribution : Distribution {
    override val circuitBreakers: CircuitBreakerProvider =
        ::InMemoryCircuitBreaker
    override val rateLimiters: RateLimitProvider = ::InMemoryRateLimiter
    override val bulkheads: BulkheadProvider = ::InMemoryBulkhead
}
