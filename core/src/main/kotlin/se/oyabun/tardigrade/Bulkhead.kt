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

interface Bulkhead {
    val maxConcurrentCalls: Int get() = 25
    val maxWaitDuration: Duration get() = Duration.ZERO

    fun configHash(): String =
        arrayOf(
            maxConcurrentCalls.toString(),
        ).contentHashCode().toString(16)

    suspend fun <T> execute(block: suspend () -> T): BulkheadOutcome<T>
}

sealed interface BulkheadOutcome<out T> {
    @JvmInline value class Ok<out T>(
        val value: T,
    ) : BulkheadOutcome<T>

    data object Full : BulkheadOutcome<Nothing>
}

suspend fun <T> BulkheadOutcome<T>.orElse(fallback: suspend () -> T): T =
    when (this) {
        is BulkheadOutcome.Ok -> value
        BulkheadOutcome.Full -> fallback()
    }
