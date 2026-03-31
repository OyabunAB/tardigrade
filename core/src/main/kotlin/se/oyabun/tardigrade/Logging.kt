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

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

object Logging {
    fun logger(func: () -> Unit): TardigradeLogger {
        val exact = func.javaClass.enclosingClass?.name ?: func.javaClass.name
        val name = exact.substringBefore("$")
        val log = KotlinLogging.logger(name)
        return TardigradeLogger(log)
    }
}

class LogAction(
    private val logFn: () -> Unit,
) {
    fun <T> before(block: () -> T) {
        logFn()
        block()
    }

    fun <T> after(block: () -> T) {
        block()
        logFn()
    }
}

class TardigradeLogger(
    private val log: KLogger,
) {
    val circuitBreaker = CircuitBreaker()
    val rateLimiter = RateLimiter()
    val bulkhead = Bulkhead()
    val retry = Retry()
    val factory = Factory()
    val config = Config()

    inner class CircuitBreaker {
        val created = Created()
        val transition = Transition()
        val rejected = Rejected()

        inner class Created {
            operator fun invoke(
                impl: String,
                name: String,
            ) = LogAction {
                log.debug { "[$impl] CircuitBreaker[$name] created" }
            }
        }

        inner class Transition {
            operator fun invoke(
                name: String,
                to: CircuitBreakerState,
            ) = LogAction { log.info { "CircuitBreaker[$name] → $to" } }
        }

        inner class Rejected {
            operator fun invoke(name: String) =
                LogAction {
                    log.debug { "CircuitBreaker[$name] rejected (OPEN)" }
                }
        }
    }

    inner class RateLimiter {
        val created = Created()
        val exceeded = Exceeded()

        inner class Created {
            operator fun invoke(
                impl: String,
                name: String,
            ) = LogAction { log.debug { "[$impl] RateLimiter[$name] created" } }
        }

        inner class Exceeded {
            operator fun invoke(name: String) =
                LogAction { log.debug { "RateLimiter[$name] limit exceeded" } }
        }
    }

    inner class Bulkhead {
        val created = Created()
        val full = Full()

        inner class Created {
            operator fun invoke(
                impl: String,
                name: String,
            ) = LogAction { log.debug { "[$impl] Bulkhead[$name] created" } }
        }

        inner class Full {
            operator fun invoke(name: String) =
                LogAction {
                    log.debug { "Bulkhead[$name] full, call rejected" }
                }
        }
    }

    inner class Retry {
        val created = Created()
        val exhausted = Exhausted()

        inner class Created {
            operator fun invoke(
                impl: String,
                name: String,
            ) = LogAction { log.debug { "[$impl] Retry[$name] created" } }
        }

        inner class Exhausted {
            operator fun invoke(name: String) =
                LogAction {
                    log.debug { "Retry[$name] exhausted all attempts" }
                }
        }
    }

    inner class Factory {
        val reset = Reset()

        inner class Reset {
            operator fun invoke() =
                LogAction { log.info { "Tardigrade reset" } }
        }
    }

    inner class Config {
        val conflict = Conflict()

        inner class Conflict {
            operator fun invoke(
                name: String,
                expected: String,
                actual: String,
            ) = LogAction {
                log.warn {
                    "Config conflict for '$name': expected=[$expected] actual=[$actual]"
                }
            }
        }
    }
}
