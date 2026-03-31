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
import se.oyabun.tardigrade.Bulkhead
import se.oyabun.tardigrade.BulkheadOutcome
import se.oyabun.tardigrade.Logging
import se.oyabun.tardigrade.hazelcast.extension.validateConfig
import kotlin.time.Duration

internal class HazelcastBulkhead(
    private val name: String,
    private val hazelcast: HazelcastInstance,
    override val maxConcurrentCalls: Int = 25,
    override val maxWaitDuration: Duration = Duration.ZERO,
) : Bulkhead {
    private val meta: IMap<String, String> =
        hazelcast.getMap(
            "tardigrade:bh:$name",
        )

    private val log = Logging.logger {}

    init {
        log.bulkhead.created("hazelcast", name).after {
            hazelcast.validateConfig(
                "tardigrade:bh:$name:config",
                name,
                configHash(),
            )
            meta.putIfAbsent("permits", maxConcurrentCalls.toString())
        }
    }

    override suspend fun <T> execute(
        block: suspend () -> T,
    ): BulkheadOutcome<T> {
        val acquired =
            withContext(Dispatchers.IO) {
                meta.lock("l")
                try {
                    val permits = meta["permits"]?.toInt() ?: 0
                    if (permits > 0) {
                        meta.put("permits", (permits - 1).toString())
                        true
                    } else {
                        false
                    }
                } finally {
                    meta.unlock("l")
                }
            }
        if (!acquired) {
            log.bulkhead.full(name).before {}
            return BulkheadOutcome.Full
        }
        return BulkheadOutcome.Ok(
            try {
                block()
            } finally {
                withContext(Dispatchers.IO) {
                    meta.lock("l")
                    try {
                        val permits = meta["permits"]?.toInt() ?: 0
                        meta.put("permits", (permits + 1).toString())
                    } finally {
                        meta.unlock("l")
                    }
                }
            },
        )
    }
}
