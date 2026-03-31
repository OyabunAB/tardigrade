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

import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

sealed interface RetryOutcome<out T> {
    @JvmInline value class Ok<out T>(
        val value: T,
    ) : RetryOutcome<T>

    data object Exhausted : RetryOutcome<Nothing>
}

interface Retry {
    val maxAttempts: Int get() = 3
    val waitDuration: Duration get() = 500.milliseconds
    val exponentialBackoffMultiplier: Double get() = 1.0
    val retryOn: Set<KClass<out Throwable>> get() = setOf(Exception::class)

    fun configHash(): String =
        arrayOf(
            maxAttempts.toString(),
            waitDuration.inWholeMilliseconds.toString(),
            exponentialBackoffMultiplier.toString(),
            retryOn.sortedBy { it.qualifiedName.orEmpty() }.toString(),
        ).contentHashCode().toString(16)

    suspend fun <T> execute(block: suspend () -> T): RetryOutcome<T>
}

suspend fun <T> RetryOutcome<T>.orElse(fallback: suspend () -> T): T =
    when (this) {
        is RetryOutcome.Ok -> value
        RetryOutcome.Exhausted -> fallback()
    }
