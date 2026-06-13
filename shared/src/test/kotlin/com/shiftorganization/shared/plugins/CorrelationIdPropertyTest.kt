package com.shiftorganization.shared.plugins

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.jqwik.api.*
import net.jqwik.api.constraints.AlphaChars
import net.jqwik.api.constraints.CharRange
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.StringLength
import org.junit.jupiter.api.Assertions.*
import com.shiftorganization.shared.exception.ForbiddenException
import com.shiftorganization.shared.plugins.configureStatusPages

/**
 * Property 21: Structured log completeness — correlation ID propagation.
 *
 * For any HTTP request, the X-Correlation-Id header value SHALL:
 * 1. Be echoed back in the response header unchanged when supplied.
 * 2. Be a valid UUID v4 when not supplied.
 * 3. Appear in error response bodies (correlationId field) when an exception is thrown.
 * 4. Be consistent between the response header and the error body.
 *
 * Validates: Requirement 9.6
 *
 * Feature: shift-organization-mvp, Property 21: Correlation ID propagation invariant
 */
@Label("Feature: shift-organization-mvp, Property 21: Correlation ID propagation invariant")
class CorrelationIdPropertyTest {

    private val uuidV4Regex = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        RegexOption.IGNORE_CASE
    )

    // ---------------------------------------------------------------------------
    // Property: supplied ID is always echoed back unchanged
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `any supplied X-Correlation-Id is echoed back unchanged in response header`(
        @ForAll @StringLength(min = 1, max = 64)
        @CharRange(from = 'a', to = 'z') correlationId: String
    ) {
        runBlocking {
            testApplication {
                application { configureEchoApp() }
                val response = client.get("/ping") {
                    header(CORRELATION_ID_HEADER, correlationId)
                }
                val returned = response.headers[CORRELATION_ID_HEADER]
                assertEquals(
                    correlationId, returned,
                    "Supplied correlation ID must be echoed back unchanged"
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Property: generated ID is always UUID v4
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `when no X-Correlation-Id is supplied the response contains a UUID v4`(
        @ForAll @StringLength(min = 1, max = 20) @AlphaChars path: String
    ) {
        runBlocking {
            testApplication {
                application { configureEchoApp() }
                val response = client.get("/ping")
                val returned = response.headers[CORRELATION_ID_HEADER]
                assertNotNull(returned, "X-Correlation-Id must be present even without request header")
                assertTrue(
                    uuidV4Regex.matches(returned!!.trim()),
                    "Generated correlation ID must be a UUID v4, got: $returned"
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Property: error body correlationId matches response header
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `error response body correlationId matches the X-Correlation-Id response header`(
        @ForAll @StringLength(min = 1, max = 64)
        @CharRange(from = 'a', to = 'z') correlationId: String
    ) {
        runBlocking {
            testApplication {
                application { configureErrorApp() }
                val response = client.get("/error") {
                    header(CORRELATION_ID_HEADER, correlationId)
                }
                val headerValue = response.headers[CORRELATION_ID_HEADER]
                val body = response.bodyAsText()
                val bodyCorrelationId = Json.parseToJsonElement(body)
                    .jsonObject["correlationId"]?.jsonPrimitive?.content

                assertEquals(correlationId, headerValue, "Header must echo supplied ID")
                assertEquals(
                    headerValue, bodyCorrelationId,
                    "Error body correlationId must match the response header"
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Property: generated ID is consistent between header and error body
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `generated correlation ID is consistent between response header and error body`() {
        runBlocking {
            testApplication {
                application { configureErrorApp() }
                val response = client.get("/error")
                val headerValue = response.headers[CORRELATION_ID_HEADER]
                val body = response.bodyAsText()
                val bodyCorrelationId = Json.parseToJsonElement(body)
                    .jsonObject["correlationId"]?.jsonPrimitive?.content

                assertNotNull(headerValue)
                assertEquals(
                    headerValue, bodyCorrelationId,
                    "Error body correlationId must equal the generated header value"
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Test application setups
    // ---------------------------------------------------------------------------

    private fun Application.configureEchoApp() {
        install(CorrelationIdPlugin)
        install(ContentNegotiation) { json() }
        routing {
            get("/ping") {
                call.respondText(call.correlationId)
            }
        }
    }

    private fun Application.configureErrorApp() {
        install(CorrelationIdPlugin)
        install(ContentNegotiation) { json() }
        configureStatusPages()
        routing {
            get("/error") {
                throw ForbiddenException("test forbidden")
            }
        }
    }
}
