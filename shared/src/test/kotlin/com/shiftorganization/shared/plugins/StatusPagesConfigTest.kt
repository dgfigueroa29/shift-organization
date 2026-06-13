package com.shiftorganization.shared.plugins

import com.shiftorganization.shared.exception.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [configureStatusPages].
 *
 * Verifies the HTTP status code mapping and consistent JSON error body shape for each
 * domain exception defined in [com.shiftorganization.shared.exception].
 *
 * Requirements: 1.1, 1.2, 1.3, 9.6
 */
class StatusPagesConfigTest {

    /**
     * Application module function wires all plugins and a route per exception type.
     * Defined as an Application extension to avoid receiver resolution issues in Kotlin 2.x.
     */
    private fun Application.configureTestApp() {
        install(CorrelationIdPlugin)
        install(ContentNegotiation) { json() }
        configureStatusPages()
        routing {
            get("/not-found") { throw NotFoundException("42", "Property") }
            get("/forbidden") { throw ForbiddenException() }
            get("/unauthorized") { throw UnauthorizedException() }
            get("/conflict") { throw ConflictException(listOf("booking-id-1", "booking-id-2")) }
            get("/conflict-empty") { throw ConflictException(emptyList()) }
            get("/deadline") { throw CancellationDeadlineException("booking-id-3") }
            get("/opensearch-down") { throw OpenSearchUnavailableException() }
            get("/internal-error") { throw InternalServerException("EventBridge failed") }
            get("/generic-error") { throw RuntimeException("something broke") }
        }
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { configureTestApp() }
        block()
    }

    /** Parses the JSON body and asserts the three required fields are present and non-blank. */
    private fun assertErrorBody(body: String, expectedError: String) {
        val json = Json.parseToJsonElement(body).jsonObject
        val errorField = json["error"]?.jsonPrimitive?.content
        val messageField = json["message"]?.jsonPrimitive?.content
        val correlationIdField = json["correlationId"]?.jsonPrimitive?.content

        assertEquals(expectedError, errorField, "error field mismatch")
        assertNotNull(messageField, "message field must be present")
        assertTrue(messageField!!.isNotBlank(), "message field must not be blank")
        assertNotNull(correlationIdField, "correlationId field must be present")
        assertTrue(correlationIdField!!.isNotBlank(), "correlationId must not be blank")
    }

    @Nested
    inner class `NotFoundException mapping` {

        @Test
        fun `maps to HTTP 404`() = testApp {
            val response = client.get("/not-found")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `response body has error NOT_FOUND with required fields`() = testApp {
            val body = client.get("/not-found").bodyAsText()
            assertErrorBody(body, "NOT_FOUND")
        }
    }

    @Nested
    inner class `ForbiddenException mapping` {

        @Test
        fun `maps to HTTP 403`() = testApp {
            val response = client.get("/forbidden")
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

        @Test
        fun `response body has error FORBIDDEN with required fields`() = testApp {
            val body = client.get("/forbidden").bodyAsText()
            assertErrorBody(body, "FORBIDDEN")
        }
    }

    @Nested
    inner class `UnauthorizedException mapping` {

        @Test
        fun `maps to HTTP 401`() = testApp {
            val response = client.get("/unauthorized")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `response body has error UNAUTHORIZED with required fields`() = testApp {
            val body = client.get("/unauthorized").bodyAsText()
            assertErrorBody(body, "UNAUTHORIZED")
        }
    }

    @Nested
    inner class `ConflictException mapping` {

        @Test
        fun `maps to HTTP 409`() = testApp {
            val response = client.get("/conflict")
            assertEquals(HttpStatusCode.Conflict, response.status)
        }

        @Test
        fun `response body has error CONFLICT with required fields`() = testApp {
            val body = client.get("/conflict").bodyAsText()
            assertErrorBody(body, "CONFLICT")
        }

        @Test
        fun `conflictingBookingIds are included in the body`() = testApp {
            val body = client.get("/conflict").bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val ids = json["conflictingBookingIds"]?.jsonArray
            assertNotNull(ids, "conflictingBookingIds should be present when list is non-empty")
            val idStrings = ids!!.map { it.jsonPrimitive.content }
            assertTrue(idStrings.contains("booking-id-1"))
            assertTrue(idStrings.contains("booking-id-2"))
        }

        @Test
        fun `conflictingBookingIds is absent or null when list is empty`() = testApp {
            val body = client.get("/conflict-empty").bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val ids = json["conflictingBookingIds"]
            assertTrue(
                ids == null || ids is JsonNull,
                "conflictingBookingIds should be absent or null when list is empty"
            )
        }
    }

    @Nested
    inner class `CancellationDeadlineException mapping` {

        @Test
        fun `maps to HTTP 422`() = testApp {
            val response = client.get("/deadline")
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

        @Test
        fun `response body has error CANCELLATION_DEADLINE_PASSED`() = testApp {
            val body = client.get("/deadline").bodyAsText()
            assertErrorBody(body, "CANCELLATION_DEADLINE_PASSED")
        }
    }

    @Nested
    inner class `OpenSearchUnavailableException mapping` {

        @Test
        fun `maps to HTTP 503`() = testApp {
            val response = client.get("/opensearch-down")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

        @Test
        fun `response body has error SERVICE_UNAVAILABLE`() = testApp {
            val body = client.get("/opensearch-down").bodyAsText()
            assertErrorBody(body, "SERVICE_UNAVAILABLE")
        }
    }

    @Nested
    inner class `InternalServerException mapping` {

        @Test
        fun `maps to HTTP 500`() = testApp {
            val response = client.get("/internal-error")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }

        @Test
        fun `response body has error INTERNAL_SERVER_ERROR`() = testApp {
            val body = client.get("/internal-error").bodyAsText()
            assertErrorBody(body, "INTERNAL_SERVER_ERROR")
        }
    }

    @Nested
    inner class `Generic Throwable catch-all` {

        @Test
        fun `maps to HTTP 500`() = testApp {
            val response = client.get("/generic-error")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }

        @Test
        fun `response body has error INTERNAL_SERVER_ERROR`() = testApp {
            val body = client.get("/generic-error").bodyAsText()
            assertErrorBody(body, "INTERNAL_SERVER_ERROR")
        }
    }

    @Nested
    inner class `Correlation ID in error responses` {

        @Test
        fun `supplied correlation ID is echoed in error body when exception thrown`() = testApp {
            val suppliedId = "trace-error-test"
            val body = client.get("/not-found") {
                header(CORRELATION_ID_HEADER, suppliedId)
            }.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val correlationId = json["correlationId"]?.jsonPrimitive?.content
            assertEquals(
                suppliedId, correlationId,
                "correlationId in error body should match the supplied request header"
            )
        }

        @Test
        fun `correlation ID is present in error body even when no header supplied`() = testApp {
            val body = client.get("/forbidden").bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val correlationId = json["correlationId"]?.jsonPrimitive?.content
            assertNotNull(correlationId)
            assertTrue(correlationId!!.isNotBlank())
        }
    }
}
