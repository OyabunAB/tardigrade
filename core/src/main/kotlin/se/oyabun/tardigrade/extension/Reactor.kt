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

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.retry.Retry.backoff
import reactor.util.retry.Retry.fixedDelay
import se.oyabun.tardigrade.Bulkhead
import se.oyabun.tardigrade.BulkheadOutcome
import se.oyabun.tardigrade.CircuitBreaker
import se.oyabun.tardigrade.CircuitBreakerOutcome
import se.oyabun.tardigrade.RateLimiter
import se.oyabun.tardigrade.RateLimiterOutcome
import se.oyabun.tardigrade.Retry
import kotlin.time.toJavaDuration
import reactor.util.retry.Retry as ReactorRetry

fun <T : Any> Mono<T>.withCircuitBreaker(
    cb: CircuitBreaker,
): Mono<CircuitBreakerOutcome<T>> =
    mono { cb.execute { this@withCircuitBreaker.awaitSingle() } }

fun <T : Any> Mono<T>.withRateLimiter(
    rl: RateLimiter,
): Mono<RateLimiterOutcome<T>> =
    mono { rl.execute { this@withRateLimiter.awaitSingle() } }

fun <T : Any> Mono<T>.withBulkhead(bh: Bulkhead): Mono<BulkheadOutcome<T>> =
    mono { bh.execute { this@withBulkhead.awaitSingle() } }

fun <T : Any> Mono<T>.withRetry(retry: Retry): Mono<T> =
    retryWhen(retry.toReactorRetry())

fun <T : Any> Flux<T>.withCircuitBreaker(
    cb: CircuitBreaker,
): Flux<CircuitBreakerOutcome<T>> =
    concatMap { value -> mono { cb.execute { value } } }

fun <T : Any> Flux<T>.withRateLimiter(
    rl: RateLimiter,
): Flux<RateLimiterOutcome<T>> =
    concatMap { value -> mono { rl.execute { value } } }

fun <T : Any> Flux<T>.withBulkhead(bh: Bulkhead): Flux<BulkheadOutcome<T>> =
    concatMap { value -> mono { bh.execute { value } } }

fun <T : Any> Flux<T>.withRetry(retry: Retry): Flux<T> =
    retryWhen(retry.toReactorRetry())

@JvmName("monoCircuitBreakerOrFallback")
fun <T : Any> Mono<CircuitBreakerOutcome<T>>.orFallback(
    producer: () -> Mono<T>,
): Mono<T> =
    flatMap {
        when (it) {
            is CircuitBreakerOutcome.Ok -> Mono.just(it.value)
            CircuitBreakerOutcome.Open -> producer()
        }
    }

@JvmName("monoRateLimiterOrFallback")
fun <T : Any> Mono<RateLimiterOutcome<T>>.orFallback(
    producer: () -> Mono<T>,
): Mono<T> =
    flatMap {
        when (it) {
            is RateLimiterOutcome.Ok -> Mono.just(it.value)
            RateLimiterOutcome.Exceeded -> producer()
        }
    }

@JvmName("monoBulkheadOrFallback")
fun <T : Any> Mono<BulkheadOutcome<T>>.orFallback(
    producer: () -> Mono<T>,
): Mono<T> =
    flatMap {
        when (it) {
            is BulkheadOutcome.Ok -> Mono.just(it.value)
            BulkheadOutcome.Full -> producer()
        }
    }

@JvmName("fluxCircuitBreakerOrFallback")
fun <T : Any> Flux<CircuitBreakerOutcome<T>>.orFallback(
    producer: () -> Mono<T>,
): Flux<T> =
    concatMap {
        when (it) {
            is CircuitBreakerOutcome.Ok -> Mono.just(it.value)
            CircuitBreakerOutcome.Open -> producer()
        }
    }

@JvmName("fluxRateLimiterOrFallback")
fun <T : Any> Flux<RateLimiterOutcome<T>>.orFallback(
    producer: () -> Mono<T>,
): Flux<T> =
    concatMap {
        when (it) {
            is RateLimiterOutcome.Ok -> Mono.just(it.value)
            RateLimiterOutcome.Exceeded -> producer()
        }
    }

@JvmName("fluxBulkheadOrFallback")
fun <T : Any> Flux<BulkheadOutcome<T>>.orFallback(
    producer: () -> Mono<T>,
): Flux<T> =
    concatMap {
        when (it) {
            is BulkheadOutcome.Ok -> Mono.just(it.value)
            BulkheadOutcome.Full -> producer()
        }
    }

private fun Retry.toReactorRetry(): ReactorRetry {
    val attempts = maxAttempts.toLong() - 1
    val base = waitDuration.toJavaDuration()
    return if (exponentialBackoffMultiplier == 1.0) {
        fixedDelay(attempts, base)
            .filter { e -> retryOn.any { it.isInstance(e) } }
    } else {
        backoff(attempts, base)
            .multiplier(exponentialBackoffMultiplier)
            .filter { e -> retryOn.any { it.isInstance(e) } }
    }
}
