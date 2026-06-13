package com.shiftorganization.lambda.notifications

import com.shiftorganization.shared.notification.NotificationService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class NotificationsModuleTest {

    @Test
    fun `GET notifications health returns 200`() = testApplication {
        application { notificationsModule(mock()) }
        val response = client.get("/notifications/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST notifications sns returns 200`() = testApplication {
        application { notificationsModule(mock()) }
        val response = client.post("/notifications/sns") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TextContent("""{"Records":[{"Sns":{"Message":"{\"eventType\":\"BOOKING_CREATED\"}"}}]}""", ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
