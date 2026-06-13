package com.shiftorganization.lambda.recurringevents

import com.shiftorganization.shared.auth.COGNITO_JWT_AUTH
import com.shiftorganization.shared.domain.RecurringEvent
import com.shiftorganization.shared.domain.Role
import com.shiftorganization.shared.domain.UserPrincipal
import com.shiftorganization.shared.model.CreateRecurringEventRequest
import com.shiftorganization.shared.service.RecurringEventService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class RecurringEventsModuleTest {

    private companion object {
        fun Application.configureApp(role: Role = Role.OWNER) {
            install(Authentication) {
                bearer(COGNITO_JWT_AUTH) {
                    authenticate { UserPrincipal("user-1", role) }
                }
            }
        }
    }

    private val sampleEvent = RecurringEvent(
        propertyId = "prop-1", eventId = "evt-1",
        cronExpression = "cron(0 9 ? * MON *)", eventType = "MAINTENANCE",
        status = "active", createdAt = "2026-06-01T00:00:00Z"
    )

    @Test
    fun `POST recurring events creates event and returns 201`() = testApplication {
        val service = mock<RecurringEventService>()
        whenever(service.create(any<CreateRecurringEventRequest>(), any()))
            .thenReturn(sampleEvent)
        application {
            configureApp()
            recurringEventsModule(service)
        }
        val response = client.post("/recurring-events") {
            header(HttpHeaders.Authorization, "Bearer token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TextContent("""{"propertyId":"prop-1","cronExpression":"cron(0 9 ? * MON *)","eventType":"MAINTENANCE"}""", ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `POST recurring events rejects TENANT role with 403`() = testApplication {
        val service = mock<RecurringEventService>()
        application {
            configureApp(role = Role.TENANT)
            recurringEventsModule(service)
        }
        val response = client.post("/recurring-events") {
            header(HttpHeaders.Authorization, "Bearer token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TextContent("""{"propertyId":"prop-1","cronExpression":"cron(0 9 ? * MON *)","eventType":"MAINTENANCE"}""", ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `DELETE recurring events returns 204`() = testApplication {
        val service = mock<RecurringEventService>()
        application {
            configureApp()
            recurringEventsModule(service)
        }
        val response = client.delete("/recurring-events/evt-1") {
            header(HttpHeaders.Authorization, "Bearer token")
            parameter("propertyId", "prop-1")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        verify(service).delete("prop-1", "evt-1", UserPrincipal("user-1", Role.OWNER))
    }
}
