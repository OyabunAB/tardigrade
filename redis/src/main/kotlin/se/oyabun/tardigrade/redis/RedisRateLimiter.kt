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

import kotlinx.coroutines.future.await
import org.redisson.api.RateType
import org.redisson.api.RedissonClient
import se.oyabun.tardigrade.Logging
import se.oyabun.tardigrade.RateLimiter
import se.oyabun.tardigrade.RateLimiterOutcome
import se.oyabun.tardigrade.redis.extension.validateConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

internal class RedisRateLimiter(
    private val name: String,
    private val redisson: RedissonClient,
    override val limitForPeriod: Long = 50,
    override val limitRefreshPeriod: Duration = 1.seconds,
    override val timeoutDuration: Duration = Duration.ZERO,
) : RateLimiter {
    private var limiter: org.redisson.api.RRateLimiter

    private val log = Logging.logger {}

    init {
        val lock = redisson.getLock("tardigrade:rl:$name:init")
        lock.lock()
        try {
            redisson.validateConfig(
                "tardigrade:rl:$name:config",
                name,
                configHash(),
            )
            limiter = redisson.getRateLimiter("tardigrade:rl:$name")
            limiter.trySetRate(
                RateType.OVERALL,
                limitForPeriod,
                limitRefreshPeriod.toJavaDuration(),
            )
        } finally {
            lock.unlock()
        }
        log.rateLimiter.created("redis", name).before {}
    }

    override suspend fun <T> execute(
        block: suspend () -> T,
    ): RateLimiterOutcome<T> {
        val acquired =
            if (timeoutDuration ==
                Duration.ZERO
            ) {
                limiter.tryAcquireAsync()
            } else {
                limiter.tryAcquireAsync(timeoutDuration.toJavaDuration())
            }
        if (!acquired.await()) {
            log.rateLimiter.exceeded(name).before {}
            return RateLimiterOutcome.Exceeded
        }
        return RateLimiterOutcome.Ok(block())
    }
}
