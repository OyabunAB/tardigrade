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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface RateLimiter {
    val limitForPeriod: Long get() = 50
    val limitRefreshPeriod: Duration get() = 1.seconds
    val timeoutDuration: Duration get() = Duration.ZERO

    fun configHash(): String =
        arrayOf(
            limitForPeriod.toString(),
            limitRefreshPeriod.inWholeMilliseconds.toString(),
        ).contentHashCode().toString(16)

    suspend fun <T> execute(block: suspend () -> T): RateLimiterOutcome<T>
}

sealed interface RateLimiterOutcome<out T> {
    @JvmInline value class Ok<out T>(
        val value: T,
    ) : RateLimiterOutcome<T>

    data object Exceeded : RateLimiterOutcome<Nothing>
}

suspend fun <T> RateLimiterOutcome<T>.orElse(fallback: suspend () -> T): T =
    when (this) {
        is RateLimiterOutcome.Ok -> value
        RateLimiterOutcome.Exceeded -> fallback()
    }
