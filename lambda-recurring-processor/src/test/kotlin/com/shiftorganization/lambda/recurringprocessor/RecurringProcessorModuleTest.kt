package com.shiftorganization.lambda.recurringprocessor

import com.shiftorganization.shared.domain.RecurringEvent
import com.shiftorganization.shared.exception.NotFoundException
import com.shiftorganization.shared.service.RecurringEventProcessorService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class RecurringProcessorModuleTest {

    @Test
    fun `POST process returns 200 when event is processed`() = testApplication {
        val service = mock<RecurringEventProcessorService>()
        whenever(service.process("evt-123")).thenReturn(
            RecurringEvent().apply { eventId = "evt-123" }
        )
        application { recurringProcessorModule(service) }
        val response = client.post("/process") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TextContent("""{"eventId":"evt-123"}""", ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST process returns 404 when event not found`() = testApplication {
        val service = mock<RecurringEventProcessorService>()
        whenever(service.process("evt-unknown")).thenThrow(NotFoundException("evt-unknown", "RecurringEvent"))
        application { recurringProcessorModule(service) }
        val response = client.post("/process") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TextContent("""{"eventId":"evt-unknown"}""", ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
