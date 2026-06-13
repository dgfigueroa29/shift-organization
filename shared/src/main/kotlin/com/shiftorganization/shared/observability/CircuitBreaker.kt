package com.shiftorganization.shared.observability

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

class CircuitBreaker(
    private val name: String,
    private val failureThreshold: Int = 5,
    private val openTimeoutMs: Long = 30_000,
    private val halfOpenMaxCalls: Int = 3
) {
    private val logger = LoggerFactory.getLogger("CircuitBreaker.$name")
    private val state = AtomicReference(CircuitState.CLOSED)
    private val failureCount = AtomicInteger(0)
    private val lastOpenTime = AtomicLong(0)
    private val halfOpenCalls = AtomicInteger(0)

    fun <T> call(block: () -> T): T {
        if (state.get() == CircuitState.OPEN) {
            if (System.currentTimeMillis() - lastOpenTime.get() >= openTimeoutMs) {
                if (state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                    logger.info("Circuit $name transitioning OPEN → HALF_OPEN")
                    halfOpenCalls.set(0)
                }
            } else {
                throw CircuitBreakerOpenException(name)
            }
        }

        if (state.get() == CircuitState.HALF_OPEN && halfOpenCalls.incrementAndGet() > halfOpenMaxCalls) {
            throw CircuitBreakerOpenException(name)
        }

        return try {
            val result = block()
            onSuccess()
            result
        } catch (e: Exception) {
            onFailure()
            throw e
        }
    }

    private fun onSuccess() {
        if (state.get() == CircuitState.HALF_OPEN) {
            if (state.compareAndSet(CircuitState.HALF_OPEN, CircuitState.CLOSED)) {
                logger.info("Circuit $name transitioning HALF_OPEN → CLOSED")
                failureCount.set(0)
                halfOpenCalls.set(0)
            }
        }
    }

    private fun onFailure() {
        val current = failureCount.incrementAndGet()
        if (current >= failureThreshold) {
            if (state.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN)) {
                lastOpenTime.set(System.currentTimeMillis())
                logger.warn("Circuit $name opened after $current failures")
            } else if (state.get() == CircuitState.HALF_OPEN) {
                if (state.compareAndSet(CircuitState.HALF_OPEN, CircuitState.OPEN)) {
                    lastOpenTime.set(System.currentTimeMillis())
                    logger.warn("Circuit $name half-open test failed, reopening")
                }
            }
        }
    }

    fun isOpen() = state.get() != CircuitState.CLOSED
}

class CircuitBreakerOpenException(name: String) :
    RuntimeException("Circuit breaker '$name' is open")
