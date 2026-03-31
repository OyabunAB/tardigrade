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

import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import se.oyabun.tardigrade.Tardigrade
import se.oyabun.tardigrade.spring.annotation.WithCircuitBreaker
import kotlin.test.Test
import kotlin.test.assertFailsWith

@SpringJUnitConfig(TardigradeAspectErrorTest.Config::class)
class TardigradeAspectErrorTest {
    @Configuration
    @EnableAspectJAutoProxy
    open class Config {
        @Bean
        open fun aspect() = TardigradeAspect()

        @Bean
        open fun target() = Target()
    }

    open class Target {
        @WithCircuitBreaker("undefined-cb")
        open fun undefinedComponent(): String = "ok"

        @WithCircuitBreaker("mono-error-cb")
        open fun monoThrowsException(): Mono<String> =
            Mono.error(RuntimeException("test error"))

        @WithCircuitBreaker("blocking-error-cb")
        open fun blockingThrowsException(): String =
            throw RuntimeException("test error")
    }

    @Autowired
    lateinit var target: Target

    @AfterEach
    fun teardown() {
        Tardigrade.reset()
    }

    @Test
    fun `aspect throws when component is not defined`() {
        assertFailsWith<IllegalStateException> { target.undefinedComponent() }
    }

    @Test
    fun `aspect propagates exceptions from blocking calls`() {
        Tardigrade.define.circuitBreaker("blocking-error-cb")
        assertFailsWith<RuntimeException> { target.blockingThrowsException() }
    }

    @Test
    fun `aspect propagates exceptions from mono calls`() {
        Tardigrade.define.circuitBreaker("mono-error-cb")
        StepVerifier.create(target.monoThrowsException())
            .expectErrorSatisfies { e ->
                kotlin.test.assertTrue(e is RuntimeException)
                kotlin.test.assertEquals("test error", e.message)
            }
            .verify()
    }
}
