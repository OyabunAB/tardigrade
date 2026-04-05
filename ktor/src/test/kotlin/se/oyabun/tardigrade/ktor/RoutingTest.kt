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

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import se.oyabun.tardigrade.Retry
import se.oyabun.tardigrade.RetryOutcome
import se.oyabun.tardigrade.Tardigrade
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class RoutingTest {
    @AfterEach
    fun teardown() = Tardigrade.reset()

    @Test
    fun `withCircuitBreaker passes request when closed`() =
        testApplication {
            val cb = Tardigrade.define.circuitBreaker("cb-pass")
            application {
                routing {
                    withCircuitBreaker(cb) {
                        get("/test") { call.respondText("ok") }
                    }
                }
            }
            assertEquals(HttpStatusCode.OK, client.get("/test").status)
        }

    @Test
    fun `withCircuitBreaker responds 503 when open`() {
        val cb =
            Tardigrade.define.circuitBreaker(
                name = "cb-open",
                failureRateThreshold = 100f,
                slidingWindowSize = 2,
                minimumNumberOfCalls = 2,
                waitDurationInOpenState = 60.seconds,
                permittedCallsInHalfOpenState = 1,
            )
        runBlocking {
            repeat(
                2,
            ) { runCatching { cb.execute { error("failure") } } }
        }
        testApplication {
            application {
                routing {
                    withCircuitBreaker(cb) {
                        get("/test") { call.respondText("ok") }
                    }
                }
            }
            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                client.get("/test").status,
            )
        }
    }

    @Test
    fun `withCircuitBreaker uses custom onOpen handler`() {
        val cb =
            Tardigrade.define.circuitBreaker(
                name = "cb-custom",
                failureRateThreshold = 100f,
                slidingWindowSize = 2,
                minimumNumberOfCalls = 2,
                waitDurationInOpenState = 60.seconds,
                permittedCallsInHalfOpenState = 1,
            )
        runBlocking {
            repeat(
                2,
            ) { runCatching { cb.execute { error("failure") } } }
        }
        testApplication {
            application {
                routing {
                    withCircuitBreaker(
                        cb,
                        onOpen = { call.respond(HttpStatusCode.BadGateway) },
                    ) {
                        get("/test") { call.respondText("ok") }
                    }
                }
            }
            assertEquals(HttpStatusCode.BadGateway, client.get("/test").status)
        }
    }

    @Test
    fun `withRateLimiter passes request within limit`() =
        testApplication {
            val rl =
                Tardigrade.define.rateLimiter(
                    "rl-pass",
                    limitForPeriod = 10,
                )
            application {
                routing {
                    withRateLimiter(rl) {
                        get("/test") { call.respondText("ok") }
                    }
                }
            }
            assertEquals(HttpStatusCode.OK, client.get("/test").status)
        }

    @Test
    fun `withRateLimiter responds 429 when exceeded`() =
        testApplication {
            val rl =
                Tardigrade.define.rateLimiter(
                    name = "rl-exceeded",
                    limitForPeriod = 1,
                    limitRefreshPeriod = 60.seconds,
                )
            application {
                routing {
                    withRateLimiter(rl) {
                        get("/test") { call.respondText("ok") }
                    }
                }
            }
            assertEquals(HttpStatusCode.OK, client.get("/test").status)
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.get("/test").status,
            )
        }

    @Test
    fun `withRateLimiter uses custom onExceeded handler`() =
        testApplication {
            val rl =
                Tardigrade.define.rateLimiter(
                    name = "rl-custom",
                    limitForPeriod = 1,
                    limitRefreshPeriod = 60.seconds,
                )
            application {
                routing {
                    withRateLimiter(
                        rl,
                        onExceeded = {
                            call.respond(
                                HttpStatusCode.BadGateway,
                            )
                        },
                    ) {
                        get("/test") { call.respondText("ok") }
                    }
                }
            }
            client.get("/test")
            assertEquals(HttpStatusCode.BadGateway, client.get("/test").status)
        }

    @Test
    fun `withBulkhead passes request within capacity`() =
        testApplication {
            val bh = Tardigrade.define.bulkhead("bh-pass")
            application {
                routing {
                    withBulkhead(bh) {
                        get("/test") { call.respondText("ok") }
                    }
                }
            }
            assertEquals(HttpStatusCode.OK, client.get("/test").status)
        }

    @Test
    fun `withBulkhead responds 503 when full`() {
        val bh = Tardigrade.define.bulkhead("bh-full", maxConcurrentCalls = 1)
        val permitAcquired = java.util.concurrent.CountDownLatch(1)
        val releasePermit = java.util.concurrent.CountDownLatch(1)
        val holder =
            Thread {
                runBlocking {
                    bh.execute {
                        permitAcquired.countDown()
                        releasePermit.await()
                    }
                }
            }.also { it.start() }
        permitAcquired.await()

        testApplication {
            application {
                routing {
                    withBulkhead(bh) {
                        get("/test") { call.respondText("ok") }
                    }
                }
            }
            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                client.get("/test").status,
            )
        }

        releasePermit.countDown()
        holder.join()
    }

    @Test
    fun `withRetry passes through on success`() =
        testApplication {
            val retry = simpleRetry(3)
            application {
                routing {
                    withRetry(retry) {
                        get("/test") { call.respondText("ok") }
                    }
                }
            }
            assertEquals(HttpStatusCode.OK, client.get("/test").status)
        }

    @Test
    fun `withRetry responds 503 when exhausted`() =
        testApplication {
            val retry = alwaysExhaustedRetry()
            application {
                routing {
                    withRetry(retry) {
                        get("/test") { call.respondText("ok") }
                    }
                }
            }
            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                client.get("/test").status,
            )
        }

    @Test
    fun `withRetry uses custom onExhausted handler`() =
        testApplication {
            val retry = alwaysExhaustedRetry()
            application {
                routing {
                    withRetry(
                        retry,
                        onExhausted = {
                            call.respond(
                                HttpStatusCode.BadGateway,
                            )
                        },
                    ) {
                        get("/test") { call.respondText("ok") }
                    }
                }
            }
            assertEquals(HttpStatusCode.BadGateway, client.get("/test").status)
        }
}

private fun simpleRetry(maxAttempts: Int) =
    object : Retry {
        override val maxAttempts = maxAttempts

        override fun configHash() = maxAttempts.toString()

        override suspend fun <T> execute(
            block: suspend () -> T,
        ): RetryOutcome<T> {
            repeat(maxAttempts) {
                runCatching { return RetryOutcome.Ok(block()) }
            }
            return RetryOutcome.Exhausted
        }
    }

private fun alwaysExhaustedRetry() =
    object : Retry {
        override fun configHash() = "exhausted"

        override suspend fun <T> execute(
            block: suspend () -> T,
        ): RetryOutcome<T> = RetryOutcome.Exhausted
    }
