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
package se.oyabun.tardigrade.hazelcast.extension

import com.hazelcast.core.HazelcastInstance
import se.oyabun.tardigrade.ConfigConflictException
import se.oyabun.tardigrade.Logging

private val log = Logging.logger {}

internal fun HazelcastInstance.validateConfig(
    mapName: String,
    name: String,
    hash: String,
) {
    val map = getMap<String, String>(mapName)
    map.lock("v")
    try {
        val existing = map.get("v")
        if (existing == null) {
            map.put("v", hash)
        } else if (existing != hash) {
            log.config.conflict(name, hash, existing).before {
                throw ConfigConflictException(name, hash, existing)
            }
        }
    } finally {
        map.unlock("v")
    }
}
