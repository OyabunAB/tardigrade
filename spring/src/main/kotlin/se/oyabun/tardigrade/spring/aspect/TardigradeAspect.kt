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
package se.oyabun.tardigrade.spring.aspect

import kotlinx.coroutines.runBlocking
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE
import org.springframework.http.HttpStatus.TOO_MANY_REQUESTS
import org.springframework.web.server.ResponseStatusException
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Mono.error
import reactor.core.publisher.Mono.justOrEmpty
import se.oyabun.tardigrade.BulkheadOutcome
import se.oyabun.tardigrade.CircuitBreakerOutcome
import se.oyabun.tardigrade.RateLimiterOutcome
import se.oyabun.tardigrade.RetryOutcome
import se.oyabun.tardigrade.Tardigrade
import se.oyabun.tardigrade.extension.withBulkhead
import se.oyabun.tardigrade.extension.withCircuitBreaker
import se.oyabun.tardigrade.extension.withRateLimiter
import se.oyabun.tardigrade.extension.withRetry
import se.oyabun.tardigrade.spring.annotation.WithBulkhead
import se.oyabun.tardigrade.spring.annotation.WithCircuitBreaker
import se.oyabun.tardigrade.spring.annotation.WithRateLimiter
import se.oyabun.tardigrade.spring.annotation.WithRetry

@Aspect
class TardigradeAspect {
    @Around("@annotation(ann)")
    fun aroundCircuitBreaker(
        pjp: ProceedingJoinPoint,
        ann: WithCircuitBreaker,
    ): Any? {
        val cb = Tardigrade.circuitBreaker[ann.name]
        return when {
            isMono(pjp) -> {
                @Suppress("UNCHECKED_CAST")
                (pjp.proceed() as reactor.core.publisher.Mono<Any>).withCircuitBreaker(cb).flatMap { outcome ->
                    when (outcome) {
                        is CircuitBreakerOutcome.Ok<*> -> justOrEmpty(outcome.value)
                        CircuitBreakerOutcome.Open -> error(ResponseStatusException(SERVICE_UNAVAILABLE))
                    }
                }
            }
            isFlux(pjp) -> {
                @Suppress("UNCHECKED_CAST")
                (pjp.proceed() as reactor.core.publisher.Flux<Any>).withCircuitBreaker(cb).flatMap { outcome ->
                    when (outcome) {
                        is CircuitBreakerOutcome.Ok<*> -> justOrEmpty(outcome.value)
                        CircuitBreakerOutcome.Open -> error(ResponseStatusException(SERVICE_UNAVAILABLE))
                    }
                }
            }
            else -> {
                when (val outcome = runBlocking { cb.execute { pjp.proceed() } }) {
                    is CircuitBreakerOutcome.Ok -> outcome.value
                    CircuitBreakerOutcome.Open -> throw ResponseStatusException(SERVICE_UNAVAILABLE)
                }
            }
        }
    }

    @Around("@annotation(ann)")
    fun aroundRateLimiter(
        pjp: ProceedingJoinPoint,
        ann: WithRateLimiter,
    ): Any? {
        val rl = Tardigrade.rateLimiter[ann.name]
        return when {
            isMono(pjp) -> {
                @Suppress("UNCHECKED_CAST")
                (pjp.proceed() as reactor.core.publisher.Mono<Any>).withRateLimiter(rl).flatMap { outcome ->
                    when (outcome) {
                        is RateLimiterOutcome.Ok<*> -> justOrEmpty(outcome.value)
                        RateLimiterOutcome.Exceeded -> error(ResponseStatusException(TOO_MANY_REQUESTS))
                    }
                }
            }
            isFlux(pjp) -> {
                @Suppress("UNCHECKED_CAST")
                (pjp.proceed() as reactor.core.publisher.Flux<Any>).withRateLimiter(rl).flatMap { outcome ->
                    when (outcome) {
                        is RateLimiterOutcome.Ok<*> -> justOrEmpty(outcome.value)
                        RateLimiterOutcome.Exceeded -> error(ResponseStatusException(TOO_MANY_REQUESTS))
                    }
                }
            }
            else -> {
                when (val outcome = runBlocking { rl.execute { pjp.proceed() } }) {
                    is RateLimiterOutcome.Ok -> outcome.value
                    RateLimiterOutcome.Exceeded -> throw ResponseStatusException(TOO_MANY_REQUESTS)
                }
            }
        }
    }

    @Around("@annotation(ann)")
    fun aroundBulkhead(
        pjp: ProceedingJoinPoint,
        ann: WithBulkhead,
    ): Any? {
        val bh = Tardigrade.bulkhead[ann.name]
        return when {
            isMono(pjp) -> {
                @Suppress("UNCHECKED_CAST")
                (pjp.proceed() as reactor.core.publisher.Mono<Any>).withBulkhead(bh).flatMap { outcome ->
                    when (outcome) {
                        is BulkheadOutcome.Ok<*> -> justOrEmpty(outcome.value)
                        BulkheadOutcome.Full -> error(ResponseStatusException(SERVICE_UNAVAILABLE))
                    }
                }
            }
            isFlux(pjp) -> {
                @Suppress("UNCHECKED_CAST")
                (pjp.proceed() as reactor.core.publisher.Flux<Any>).withBulkhead(bh).flatMap { outcome ->
                    when (outcome) {
                        is BulkheadOutcome.Ok<*> -> justOrEmpty(outcome.value)
                        BulkheadOutcome.Full -> error(ResponseStatusException(SERVICE_UNAVAILABLE))
                    }
                }
            }
            else -> {
                when (val outcome = runBlocking { bh.execute { pjp.proceed() } }) {
                    is BulkheadOutcome.Ok -> outcome.value
                    BulkheadOutcome.Full -> throw ResponseStatusException(SERVICE_UNAVAILABLE)
                }
            }
        }
    }

    @Around("@annotation(ann)")
    fun aroundRetry(
        pjp: ProceedingJoinPoint,
        ann: WithRetry,
    ): Any? {
        val retry = Tardigrade.retry[ann.name]
        return when {
            isMono(pjp) -> {
                @Suppress("UNCHECKED_CAST")
                (pjp.proceed() as reactor.core.publisher.Mono<Any>).withRetry(retry)
                    .onErrorMap({ Exceptions.isRetryExhausted(it) }) {
                        ResponseStatusException(SERVICE_UNAVAILABLE)
                    }
            }
            isFlux(pjp) -> {
                @Suppress("UNCHECKED_CAST")
                (pjp.proceed() as reactor.core.publisher.Flux<Any>).withRetry(retry)
                    .onErrorMap({ Exceptions.isRetryExhausted(it) }) {
                        ResponseStatusException(SERVICE_UNAVAILABLE)
                    }
            }
            else -> {
                when (val outcome = runBlocking { retry.execute { pjp.proceed() } }) {
                    is RetryOutcome.Ok -> outcome.value
                    RetryOutcome.Exhausted -> throw ResponseStatusException(SERVICE_UNAVAILABLE)
                }
            }
        }
    }

    private fun isMono(pjp: ProceedingJoinPoint) =
        Mono::class.java.isAssignableFrom(
            (pjp.signature as MethodSignature).returnType,
        )

    private fun isFlux(pjp: ProceedingJoinPoint) =
        Flux::class.java.isAssignableFrom(
            (pjp.signature as MethodSignature).returnType,
        )
}
