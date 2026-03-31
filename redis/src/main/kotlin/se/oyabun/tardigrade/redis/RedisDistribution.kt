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
import se.oyabun.tardigrade.Distribution
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class RedisDistribution(
    private val redisson: RedissonClient,
) : Distribution {
    override val circuitBreakers = TardigradeRedis.circuitBreaker(redisson)
    override val rateLimiters = TardigradeRedis.rateLimiter(redisson)
    override val bulkheads = TardigradeRedis.bulkhead(redisson)

    private val instanceId = UUID.randomUUID().toString()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val registered = AtomicBoolean(false)

    private fun registry() =
        redisson.getMapCache<String, String>("tardigrade:instances")

    override fun instanceCount() = registry().size

    override fun register() {
        if (registered.compareAndSet(false, true)) {
            registry().put(instanceId, "", 30, TimeUnit.SECONDS)
            executor.scheduleAtFixedRate(
                { registry().put(instanceId, "", 30, TimeUnit.SECONDS) },
                15,
                15,
                TimeUnit.SECONDS,
            )
        }
    }

    override fun deregister() {
        if (registered.compareAndSet(true, false)) {
            executor.shutdown()
            registry().remove(instanceId)
        }
    }
}
