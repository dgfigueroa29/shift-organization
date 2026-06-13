package com.shiftorganization.shared.plugins

import io.ktor.server.application.*
import io.ktor.server.application.hooks.*
import io.ktor.util.*
import org.slf4j.MDC
import java.util.*

/**
 * Name of the HTTP header used to carry the correlation ID for end-to-end request tracing.
 */
const val CORRELATION_ID_HEADER = "X-Correlation-Id"

/**
 * Ktor [AttributeKey] that stores the correlation ID for the current [ApplicationCall].
 * Other parts of the application can read it via `call.correlationId`.
 */
val CorrelationIdKey: AttributeKey<String> = AttributeKey("CorrelationId")

/**
 * Retrieves the correlation ID stored in this call's attributes.
 * Only available after [CorrelationIdPlugin] has been installed and the request pipeline has run.
 */
val ApplicationCall.correlationId: String
    get() = attributes[CorrelationIdKey]

/**
 * Ktor [AttributeKey] that stores the request start timestamp (epoch millis) for latency computation.
 * Set by [CorrelationIdPlugin.onCall] before any routing, read by CallLogging format closures.
 */
val CallStartTimeKey: AttributeKey<Long> = AttributeKey("startTime")

/**
 * Ktor [ApplicationPlugin] that handles correlation ID propagation.
 *
 * Behaviour:
 * 1. Reads the `X-Correlation-Id` request header.
 * 2. If the header is absent or blank, generates a new random UUID v4.
 * 3. Stores the value in the [ApplicationCall] attributes under [CorrelationIdKey].
 * 4. Puts the value into SLF4J [MDC] under the key `"correlationId"` so it is included in
 *    every structured log entry emitted during the request lifecycle.
 * 5. Appends the value to the response as an `X-Correlation-Id` header.
 *
 * Requirements: 9.6
 */
val CorrelationIdPlugin: ApplicationPlugin<Unit> = createApplicationPlugin(
    name = "CorrelationIdPlugin"
) {
    onCall { call ->
        // 0 – capture request start time for accurate latency computation
        call.attributes.put(CallStartTimeKey, System.currentTimeMillis())

        // 1 & 2 – read existing header or generate a fresh UUID v4
        val correlationId = call.request.headers[CORRELATION_ID_HEADER]
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

        // 3 – store in call attributes for downstream access
        call.attributes.put(CorrelationIdKey, correlationId)

        // 4 – propagate to MDC for structured log entries
        MDC.put("correlationId", correlationId)

        // 5 – echo back in the response header
        call.response.headers.append(CORRELATION_ID_HEADER, correlationId)
    }

    // Clean up MDC after the call finishes to avoid leaking state in thread-pool threads
    on(ResponseSent) { call ->
        MDC.remove("correlationId")
    }
}
