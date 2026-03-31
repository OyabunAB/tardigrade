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
import com.hazelcast.map.IMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.oyabun.tardigrade.Logging
import se.oyabun.tardigrade.RateLimiter
import se.oyabun.tardigrade.RateLimiterOutcome
import se.oyabun.tardigrade.hazelcast.extension.validateConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class HazelcastRateLimiter(
    private val name: String,
    private val hazelcast: HazelcastInstance,
    override val limitForPeriod: Long = 50,
    override val limitRefreshPeriod: Duration = 1.seconds,
    override val timeoutDuration: Duration = Duration.ZERO,
) : RateLimiter {
    private val meta: IMap<String, String> =
        hazelcast.getMap(
            "tardigrade:rl:$name",
        )

    private val log = Logging.logger {}

    init {
        log.rateLimiter.created("hazelcast", name).after {
            hazelcast.validateConfig(
                "tardigrade:rl:$name:config",
                name,
                configHash(),
            )
        }
    }

    override suspend fun <T> execute(
        block: suspend () -> T,
    ): RateLimiterOutcome<T> {
        val acquired =
            withContext(Dispatchers.IO) {
                meta.lock("l")
                try {
                    val now = System.currentTimeMillis()
                    val windowStart = meta["window_start"]?.toLong() ?: 0L
                    val periodMs = limitRefreshPeriod.inWholeMilliseconds

                    val (count, start) =
                        if (now - windowStart >= periodMs) {
                            meta.put("window_start", now.toString())
                            0L to now
                        } else {
                            (meta["count"]?.toLong() ?: 0L) to windowStart
                        }

                    if (count < limitForPeriod) {
                        meta.put("count", (count + 1).toString())
                        true
                    } else {
                        false
                    }
                } finally {
                    meta.unlock("l")
                }
            }
        if (!acquired) {
            log.rateLimiter.exceeded(name).before {}
            return RateLimiterOutcome.Exceeded
        }
        return RateLimiterOutcome.Ok(block())
    }
}
