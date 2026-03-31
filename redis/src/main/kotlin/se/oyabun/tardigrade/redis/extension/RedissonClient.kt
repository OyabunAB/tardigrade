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
package se.oyabun.tardigrade.redis.extension

import org.redisson.api.RedissonClient
import se.oyabun.tardigrade.ConfigConflictException
import se.oyabun.tardigrade.Logging

private val log = Logging.logger {}

internal fun RedissonClient.validateConfig(
    key: String,
    name: String,
    hash: String,
) {
    val bucket = getBucket<String>(key)
    bucket.setIfAbsent(hash)
    val stored = bucket.get()
    if (stored != hash) {
        log.config.conflict(name, hash, stored).before {
            throw ConfigConflictException(name, hash, stored)
        }
    }
}
