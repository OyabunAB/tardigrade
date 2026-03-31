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

enum class CircuitBreakerState { CLOSED, OPEN, HALF_OPEN }

interface CircuitBreaker {
    val failureRateThreshold: Float get() = 50f
    val slidingWindowSize: Int get() = 100
    val minimumNumberOfCalls: Int get() = 10
    val waitDurationInOpenState: Duration get() = 60.seconds
    val permittedCallsInHalfOpenState: Int get() = 5

    fun configHash(): String =
        arrayOf(
            failureRateThreshold.toString(),
            slidingWindowSize.toString(),
            minimumNumberOfCalls.toString(),
            waitDurationInOpenState.inWholeMilliseconds.toString(),
            permittedCallsInHalfOpenState.toString(),
        ).contentHashCode().toString(16)

    suspend fun state(): CircuitBreakerState

    suspend fun <T> execute(block: suspend () -> T): CircuitBreakerOutcome<T>
}

sealed interface CircuitBreakerOutcome<out T> {
    @JvmInline value class Ok<out T>(
        val value: T,
    ) : CircuitBreakerOutcome<T>

    data object Open : CircuitBreakerOutcome<Nothing>
}

suspend fun <T> CircuitBreakerOutcome<T>.orElse(fallback: suspend () -> T): T =
    when (this) {
        is CircuitBreakerOutcome.Ok -> value
        CircuitBreakerOutcome.Open -> fallback()
    }
