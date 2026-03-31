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
import org.redisson.api.RedissonClient
import se.oyabun.tardigrade.Bulkhead
import se.oyabun.tardigrade.BulkheadOutcome
import se.oyabun.tardigrade.Logging
import se.oyabun.tardigrade.redis.extension.validateConfig
import kotlin.time.Duration
import kotlin.time.toJavaDuration

internal class RedisBulkhead(
    private val name: String,
    private val redisson: RedissonClient,
    override val maxConcurrentCalls: Int = 25,
    override val maxWaitDuration: Duration = Duration.ZERO,
) : Bulkhead {
    private var semaphore: org.redisson.api.RSemaphore

    private val log = Logging.logger {}

    init {
        val lock = redisson.getLock("tardigrade:bh:$name:init")
        lock.lock()
        try {
            redisson.validateConfig(
                "tardigrade:bh:$name:config",
                name,
                configHash(),
            )
            semaphore = redisson.getSemaphore("tardigrade:bh:$name")
            semaphore.trySetPermits(maxConcurrentCalls)
        } finally {
            lock.unlock()
        }
        log.bulkhead.created("redis", name).before {}
    }

    override suspend fun <T> execute(
        block: suspend () -> T,
    ): BulkheadOutcome<T> {
        val acquired =
            if (maxWaitDuration ==
                Duration.ZERO
            ) {
                semaphore.tryAcquireAsync()
            } else {
                semaphore.tryAcquireAsync(1, maxWaitDuration.toJavaDuration())
            }
        if (!acquired.await()) {
            log.bulkhead.full(name).before {}
            return BulkheadOutcome.Full
        }
        return BulkheadOutcome.Ok(
            try {
                block()
            } finally {
                semaphore.releaseAsync().await()
            },
        )
    }
}
