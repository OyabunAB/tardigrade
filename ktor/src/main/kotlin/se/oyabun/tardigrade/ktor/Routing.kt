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
package se.oyabun.tardigrade.ktor

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.pipeline.PipelineContext
import se.oyabun.tardigrade.Bulkhead
import se.oyabun.tardigrade.BulkheadOutcome
import se.oyabun.tardigrade.CircuitBreaker
import se.oyabun.tardigrade.CircuitBreakerOutcome
import se.oyabun.tardigrade.RateLimiter
import se.oyabun.tardigrade.RateLimiterOutcome
import se.oyabun.tardigrade.Retry
import se.oyabun.tardigrade.RetryOutcome

private object CircuitBreakerSelector : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ) = RouteSelectorEvaluation.Transparent

    override fun toString() = "(circuit-breaker)"
}

private object RateLimiterSelector : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ) = RouteSelectorEvaluation.Transparent

    override fun toString() = "(rate-limiter)"
}

private object BulkheadSelector : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ) = RouteSelectorEvaluation.Transparent

    override fun toString() = "(bulkhead)"
}

private object RetrySelector : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ) = RouteSelectorEvaluation.Transparent

    override fun toString() = "(retry)"
}

fun Route.withCircuitBreaker(
    circuitBreaker: CircuitBreaker,
    onOpen: suspend PipelineContext<Unit, PipelineCall>.() -> Unit = {
        call.respond(HttpStatusCode.ServiceUnavailable)
    },
    build: Route.() -> Unit,
): Route =
    createChild(CircuitBreakerSelector).apply {
        (this as RoutingNode).intercept(ApplicationCallPipeline.Call) {
            val outcome = circuitBreaker.execute { proceed() }
            if (outcome is CircuitBreakerOutcome.Open) {
                onOpen()
                finish()
            }
        }
        build()
    }

fun Route.withRateLimiter(
    rateLimiter: RateLimiter,
    onExceeded: suspend PipelineContext<Unit, PipelineCall>.() -> Unit = {
        call.respond(HttpStatusCode.TooManyRequests)
    },
    build: Route.() -> Unit,
): Route =
    createChild(RateLimiterSelector).apply {
        (this as RoutingNode).intercept(ApplicationCallPipeline.Call) {
            val outcome = rateLimiter.execute { proceed() }
            if (outcome is RateLimiterOutcome.Exceeded) {
                onExceeded()
                finish()
            }
        }
        build()
    }

fun Route.withBulkhead(
    bulkhead: Bulkhead,
    onFull: suspend PipelineContext<Unit, PipelineCall>.() -> Unit = {
        call.respond(HttpStatusCode.ServiceUnavailable)
    },
    build: Route.() -> Unit,
): Route =
    createChild(BulkheadSelector).apply {
        (this as RoutingNode).intercept(ApplicationCallPipeline.Call) {
            val outcome = bulkhead.execute { proceed() }
            if (outcome is BulkheadOutcome.Full) {
                onFull()
                finish()
            }
        }
        build()
    }

fun Route.withRetry(
    retry: Retry,
    onExhausted: suspend PipelineContext<Unit, PipelineCall>.() -> Unit = {
        call.respond(HttpStatusCode.ServiceUnavailable)
    },
    build: Route.() -> Unit,
): Route =
    createChild(RetrySelector).apply {
        (this as RoutingNode).intercept(ApplicationCallPipeline.Call) {
            val outcome = retry.execute { proceed() }
            if (outcome is RetryOutcome.Exhausted) {
                onExhausted()
                finish()
            }
        }
        build()
    }
