    ▗      ▌▘       ▌
    ▜▘▀▌▛▘▛▌▌▛▌▛▘▀▌▛▌█▌
    ▐▖█▌▌ ▙▌▌▙▌▌ █▌▙▌▙▖
             ▄▌
---

[![Build](https://github.com/OyabunAB/tardigrade/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/OyabunAB/tardigrade/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

# Tardigrade resilience library

Tardigrade is a Kotlin resilience library for building fault-tolerant applications. It provides four composable patterns:

- **Circuit Breaker**: Prevent cascading failures by failing fast when a service is unavailable
- **Rate Limiter**: Control request throughput to avoid overwhelming dependent services  
- **Bulkhead**: Isolate concurrent requests to prevent thread starvation
- **Retry**: Automatically retry transient failures with configurable backoff

All components are **name-scoped, config-conflict-safe, and support distributed backends** (Redis, Hazelcast) for shared state across instances.

---

## Quick Start

### In-Memory (Single Instance)

```kotlin
// Define components once (cached by name)
Tardigrade.define.circuitBreaker("api", minimumNumberOfCalls = 10, waitDurationInOpenState = 30.seconds)
Tardigrade.define.rateLimiter("api", limitForPeriod = 100, limitRefreshPeriod = 1.seconds)
Tardigrade.define.bulkhead("api", maxConcurrentCalls = 20)
Tardigrade.define.retry("api", maxAttempts = 3, waitDuration = 200.milliseconds)

// Recall by name anywhere
val cb = Tardigrade.circuitBreaker["api"]
val result = cb.execute { service.call() }
```

### Distributed Backends (Redis / Hazelcast)

```kotlin
// Define components with shared state across instances
Tardigrade.define.circuitBreaker(
    "api",
    redisson,  // or: hazelcast
    minimumNumberOfCalls = 10,
    waitDurationInOpenState = 30.seconds,
)

// State is coordinated across all instances
val result = Tardigrade.circuitBreaker["api"].execute { service.call() }
```

---

## Distribution Algorithms

When using distributed backends (Redis, Hazelcast), components can scale across instances:

### AsIs (Default)
Each instance maintains its own state. Suitable when you have a small cluster or state doesn't need to be coordinated:

```kotlin
Tardigrade.define.circuitBreaker(
    "api",
    redisson,
    algorithm = DistributionAlgorithm.AsIs,  // ← State NOT shared
)
```

### EvenlyDivided
Split limits evenly across registered instances. Use when you want to coordinate limits across a cluster:

```kotlin
Tardigrade.define.rateLimiter(
    "api",
    redisson,
    limitForPeriod = 100,  // Total across cluster
    algorithm = DistributionAlgorithm.EvenlyDivided,  // ← Each instance gets 100/N
)

// With 2 instances: each gets 50 permits per period
// With 1 instance: gets 100 permits per period
```

**Note**: This only applies to **Circuit Breaker**, **Rate Limiter**, and **Bulkhead**. **Retry** is always per-instance (local transient failure handling).

---

## Error Handling

Each component returns a typed outcome:

```kotlin
// Circuit Breaker
when (val outcome = cb.execute { service.call() }) {
    is CircuitBreakerOutcome.Ok -> outcome.value
    CircuitBreakerOutcome.Open -> handleOpen()
}

// Rate Limiter
when (val outcome = rl.execute { service.call() }) {
    is RateLimiterOutcome.Ok -> outcome.value
    RateLimiterOutcome.Exceeded -> handleExceeded()
}

// Bulkhead
when (val outcome = bh.execute { service.call() }) {
    is BulkheadOutcome.Ok -> outcome.value
    BulkheadOutcome.Full -> handleFull()
}

// Retry
when (val outcome = retry.execute { service.call() }) {
    is RetryOutcome.Ok -> outcome.value
    RetryOutcome.Exhausted -> handleExhausted()
}
```

Or use the `orElse` helper:

```kotlin
val result = cb.execute { service.call() }
    .orElse { throw ServiceUnavailableException() }
```

### Exceptions in Components

- **Undefined component**: `IllegalStateException` thrown when accessing undefined component name
- **Config conflict**: `ConfigConflictException` thrown when defining same component with different config
- **Service exceptions**: User code exceptions propagate through; component catches them for outcome recording

---

## Logging

Tardigrade uses **SLF4J** for logging. You must provide a logging implementation:

```gradle
// In your build.gradle.kts
runtimeOnly("ch.qos.logback:logback-classic:1.5.18")  // Or your preferred SLF4J binding
```

Tardigrade logs at DEBUG level:
- Component creation
- State transitions (circuit breaker only)
- Rejections (circuit breaker OPEN, rate limiter exceeded, bulkhead full)
- Retry exhaustion
- Config conflicts

To adjust log levels, configure your logging backend (e.g., `logback.xml`):

```xml
<configuration>
  <logger name="se.oyabun.tardigrade" level="INFO" />
</configuration>
```

---

## Threading Model

### Concurrency Safety

All components are **thread-safe and coroutine-safe**. Use from:
- Blocking code: `runBlocking { ... }`
- Suspend functions: `suspend fun { ... }`
- Reactive: `Mono`, `Flux`

### Distributed Backend Blocking

Redis and Hazelcast operations use async APIs but may block on:
- Lock acquisition during initialization
- Semaphore operations (bulkhead, circuit breaker half-open)
- Network I/O (timeouts handled by underlying client)

In-memory implementations use lightweight synchronization (ConcurrentHashMap, AtomicReference) and never block indefinitely.

### Event-Driven State Updates (Hazelcast)

Hazelcast maintains a local cache of component state updated by distributed events. Decisions use cached state for performance:
- Fast-path rejection for OPEN circuits uses local cache
- Outcome recording re-checks actual state to ensure consistency

---

### Blocking Code

```kotlin
// Execute with pattern matching
val result = when (val outcome = Tardigrade.circuitBreaker["api"].execute { service.call() }) {
    is CircuitBreakerOutcome.Ok -> outcome.value
    CircuitBreakerOutcome.Open -> handleUnavailable()
}

// Or use orElse helper
val result = Tardigrade.rateLimiter["api"].execute { service.call() }
    .orElse { cachedValue() }

// Compose components
val result = Tardigrade.circuitBreaker["api"].execute {
    when (val rlOutcome = Tardigrade.rateLimiter["api"].execute { service.call() }) {
        is RateLimiterOutcome.Ok -> rlOutcome.value
        RateLimiterOutcome.Exceeded -> handleRateLimited()
    }
}
```

### Coroutines

```kotlin
suspend fun fetch(): String = 
    Tardigrade.retry["api"].execute {
        service.fetch()
    }.orElse { "fallback" }

// Use in coroutine context
runBlocking {
    val result = fetch()
}
```

### Reactive (Mono / Flux)

```kotlin
// Extensions wrap outcomes into Mono/Flux
Mono.just(request)
    .withCircuitBreaker(Tardigrade.circuitBreaker["api"])
    .onErrorMap { _ -> ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE) }
    .subscribe()

Flux.range(1, 100)
    .withRateLimiter(Tardigrade.rateLimiter["api"])
    .onErrorMap { _ -> ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS) }
    .subscribe()
```

### Flow (Kotlin Coroutines)

```kotlin
flowOf(1, 2, 3)
    .withBulkhead(Tardigrade.bulkhead["api"])
    .onCompletion { cause -> 
        if (cause != null) handleError(cause)
    }
    .collect { item -> process(item) }
```

---

## Integration Modules

### Spring Boot

Add the `tardigrade-spring` dependency for declarative resilience guards on controller methods.

Define components at startup:

```kotlin
@Configuration
class ResilienceConfig {
    @Bean
    fun apiCircuitBreaker() = Tardigrade.define.circuitBreaker(
        "api",
        minimumNumberOfCalls = 10,
        waitDurationInOpenState = 30.seconds,
    )
    // ... other components
}
```

Annotate controller methods (works with Spring MVC and WebFlux):

```kotlin
@RestController
class UserController {
    @GetMapping("/{id}")
    @WithCircuitBreaker("api")
    fun getUser(@PathVariable id: String): Mono<User> = service.findUser(id)

    @PostMapping
    @WithRateLimiter("api")
    @WithRetry("api")
    fun createUser(@RequestBody user: User): Mono<User> = service.create(user)
}
```

Catches resilience rejections and converts to HTTP responses:

| Guard | Response |
|-------|----------|
| Circuit Breaker OPEN | `503 Service Unavailable` |
| Rate Limiter Exceeded | `429 Too Many Requests` |
| Bulkhead Full | `503 Service Unavailable` |
| Retry Exhausted | `503 Service Unavailable` |

Customize via `@ControllerAdvice`:

```kotlin
@ControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ResponseStatusException::class)
    fun handle(e: ResponseStatusException) = ResponseEntity
        .status(e.statusCode)
        .body("Service temporarily unavailable. Please retry later.")
}
```

### Ktor

Add the `tardigrade-ktor` dependency for resilience guards on routes.

Define components at startup, then apply guards via routing DSL:

```kotlin
routing {
    withCircuitBreaker(Tardigrade.circuitBreaker["api"]) {
        post("/users") { call.respond(service.create()) }
    }
    
    withRateLimiter(Tardigrade.rateLimiter["api"]) {
        get("/status") { call.respond(service.status()) }
    }
}
```

### Structure
    ╔════════════════════╗
    ║ Tardigrade project ║
    ╚═╤══════════════════╝
      │   ╔════════════════════╗
      ├───╢ Tardigrade testing ║
      │   ╚════════════════════╝
      │   ╔═════════════════╗
      ├───╢ Tardigrade core ║
      │   ╚═════════════════╝
      │   ╔══════════════════╗
      ├───╢ Tardigrade redis ║
      │   ╚══════════════════╝
      │   ╔══════════════════════╗
      ├───╢ Tardigrade hazelcast ║
      │   ╚══════════════════════╝
      │   ╔═════════════════╗
      ├───╢ Tardigrade ktor ║
      │   ╚═════════════════╝
      │   ╔═══════════════════════╗
      └───╢ Tardigrade spring     ║
          ╚═══════════════════════╝
