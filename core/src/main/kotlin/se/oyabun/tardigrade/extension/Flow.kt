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
package se.oyabun.tardigrade.extension

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import se.oyabun.tardigrade.Bulkhead
import se.oyabun.tardigrade.BulkheadOutcome
import se.oyabun.tardigrade.CircuitBreaker
import se.oyabun.tardigrade.CircuitBreakerOutcome
import se.oyabun.tardigrade.RateLimiter
import se.oyabun.tardigrade.RateLimiterOutcome
import se.oyabun.tardigrade.Retry
import se.oyabun.tardigrade.RetryExhaustedException
import se.oyabun.tardigrade.RetryOutcome

fun <T> Flow<T>.withCircuitBreaker(
    cb: CircuitBreaker,
): Flow<CircuitBreakerOutcome<T>> =
    flow {
        collect { value -> emit(cb.execute { value }) }
    }

fun <T> Flow<T>.withRateLimiter(rl: RateLimiter): Flow<RateLimiterOutcome<T>> =
    flow {
        collect { value -> emit(rl.execute { value }) }
    }

fun <T> Flow<T>.withBulkhead(bh: Bulkhead): Flow<BulkheadOutcome<T>> =
    flow {
        collect { value -> emit(bh.execute { value }) }
    }

fun <T> Flow<T>.withRetry(retry: Retry): Flow<T> =
    flow {
        val outcome = retry.execute { collect { emit(it) } }
        if (outcome is RetryOutcome.Exhausted) {
            throw RetryExhaustedException(
                retry.configHash(),
            )
        }
    }

@JvmName("flowCircuitBreakerOrFallback")
fun <T> Flow<CircuitBreakerOutcome<T>>.orFallback(
    producer: suspend () -> T,
): Flow<T> =
    flow {
        collect {
            when (val outcome = it) {
                is CircuitBreakerOutcome.Ok -> emit(outcome.value)
                CircuitBreakerOutcome.Open -> emit(producer())
            }
        }
    }

@JvmName("flowRateLimiterOrFallback")
fun <T> Flow<RateLimiterOutcome<T>>.orFallback(
    producer: suspend () -> T,
): Flow<T> =
    flow {
        collect {
            when (val outcome = it) {
                is RateLimiterOutcome.Ok -> emit(outcome.value)
                RateLimiterOutcome.Exceeded -> emit(producer())
            }
        }
    }

@JvmName("flowBulkheadOrFallback")
fun <T> Flow<BulkheadOutcome<T>>.orFallback(
    producer: suspend () -> T,
): Flow<T> =
    flow {
        collect {
            when (val outcome = it) {
                is BulkheadOutcome.Ok -> emit(outcome.value)
                BulkheadOutcome.Full -> emit(producer())
            }
        }
    }
