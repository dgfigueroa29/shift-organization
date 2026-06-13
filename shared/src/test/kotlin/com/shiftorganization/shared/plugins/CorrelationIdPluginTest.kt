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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [CorrelationIdPlugin].
 *
 * Verifies:
 * - Requirement 9.6: correlation ID is included in every HTTP response header (X-Correlation-Id)
 * - When the request carries X-Correlation-Id, the same value is echoed back.
 * - When the request does NOT carry X-Correlation-Id, a new UUID v4 is generated and returned.
 * - The generated/received correlation ID is stored in the call attributes.
 */
class CorrelationIdPluginTest {

    private fun Application.configureTestApp() {
        install(CorrelationIdPlugin)
        install(ContentNegotiation) { json() }
        routing {
            get("/ping") {
                val cid = call.correlationId
                call.respondText(cid)
            }
        }
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { configureTestApp() }
        block()
    }

    @Nested
    inner class `when request has X-Correlation-Id header` {

        @Test
        fun `response contains the same correlation ID`() = testApp {
            val suppliedId = "my-trace-id-1234"
            val response = client.get("/ping") {
                header(CORRELATION_ID_HEADER, suppliedId)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(suppliedId, response.headers[CORRELATION_ID_HEADER])
        }

        @Test
        fun `response body (call attribute) contains the same correlation ID`() = testApp {
            val suppliedId = "trace-abc-xyz"
            val response = client.get("/ping") {
                header(CORRELATION_ID_HEADER, suppliedId)
            }
            assertEquals(suppliedId, response.bodyAsText())
        }

        @Test
        fun `blank header is treated as absent and a new UUID is generated`() = testApp {
            val response = client.get("/ping") {
                header(CORRELATION_ID_HEADER, "   ")
            }
            val returnedId = response.headers[CORRELATION_ID_HEADER]
            assertNotNull(returnedId, "Response must include X-Correlation-Id")
            val uuidRegex = Regex(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
                RegexOption.IGNORE_CASE
            )
            assertTrue(
                uuidRegex.matches(returnedId!!.trim()),
                "Generated ID should be a UUID v4, got: $returnedId"
            )
        }
    }

    @Nested
    inner class `when request has no X-Correlation-Id header` {

        @Test
        fun `response contains a generated UUID v4`() = testApp {
            val response = client.get("/ping")
            val returnedId = response.headers[CORRELATION_ID_HEADER]
            assertNotNull(returnedId, "Response must include X-Correlation-Id even when not supplied")
            val uuidRegex = Regex(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
                RegexOption.IGNORE_CASE
            )
            assertTrue(
                uuidRegex.matches(returnedId!!.trim()),
                "Generated ID should be a UUID v4, got: $returnedId"
            )
        }

        @Test
        fun `two consecutive requests without header receive different correlation IDs`() = testApp {
            val id1 = client.get("/ping").headers[CORRELATION_ID_HEADER]
            val id2 = client.get("/ping").headers[CORRELATION_ID_HEADER]
            assertNotNull(id1)
            assertNotNull(id2)
            assertNotEquals(id1, id2, "Each request without a header should receive a unique ID")
        }

        @Test
        fun `call attribute matches the response header`() = testApp {
            val response = client.get("/ping")
            val headerValue = response.headers[CORRELATION_ID_HEADER]
            val bodyValue = response.bodyAsText()
            assertEquals(
                headerValue, bodyValue,
                "The call attribute should equal the response header value"
            )
        }
    }
}
