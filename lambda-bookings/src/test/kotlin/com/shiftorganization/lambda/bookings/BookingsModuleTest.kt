package com.shiftorganization.lambda.bookings

import com.shiftorganization.shared.auth.COGNITO_JWT_AUTH
import com.shiftorganization.shared.domain.Booking
import com.shiftorganization.shared.domain.Role
import com.shiftorganization.shared.domain.UserPrincipal
import com.shiftorganization.shared.model.CreateBookingRequest
import com.shiftorganization.shared.service.BookingService
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
import java.time.LocalDate
import java.time.OffsetDateTime

class BookingsModuleTest {

    private companion object {
        fun Application.configureApp(role: Role = Role.TENANT) {
            install(Authentication) {
                bearer(COGNITO_JWT_AUTH) {
                    authenticate { UserPrincipal("user-1", role) }
                }
            }
        }
    }

    private val sampleBooking = Booking(
        id = "bkg-1", propertyId = "prop-1", tenantId = "user-1",
        startDate = LocalDate.parse("2026-06-10"), endDate = LocalDate.parse("2026-06-15"),
        status = "confirmed", createdAt = OffsetDateTime.now(), updatedAt = OffsetDateTime.now()
    )

    @Test
    fun `POST bookings creates booking and returns 201`() = testApplication {
        val service = mock<BookingService>()
        whenever(service.create(any<CreateBookingRequest>(), any())).thenReturn(sampleBooking)
        application {
            configureApp()
            bookingsModule(service)
        }
        val response = client.post("/bookings") {
            header(HttpHeaders.Authorization, "Bearer token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TextContent("""{"propertyId":"prop-1","startDate":"2026-06-10","endDate":"2026-06-15"}""", ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `POST bookings rejects OWNER role with 403`() = testApplication {
        val service = mock<BookingService>()
        application {
            configureApp(role = Role.OWNER)
            bookingsModule(service)
        }
        val response = client.post("/bookings") {
            header(HttpHeaders.Authorization, "Bearer token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TextContent("""{"propertyId":"prop-1","startDate":"2026-06-10","endDate":"2026-06-15"}""", ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET bookings by id returns 200 when found`() = testApplication {
        val service = mock<BookingService>()
        whenever(service.findById("bkg-1", UserPrincipal("user-1", Role.TENANT)))
            .thenReturn(sampleBooking)
        application {
            configureApp()
            bookingsModule(service)
        }
        val response = client.get("/bookings/bkg-1") {
            header(HttpHeaders.Authorization, "Bearer token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `DELETE bookings by id cancels booking and returns 204`() = testApplication {
        val service = mock<BookingService>()
        application {
            configureApp()
            bookingsModule(service)
        }
        val response = client.delete("/bookings/bkg-1") {
            header(HttpHeaders.Authorization, "Bearer token")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        verify(service).cancel("bkg-1", UserPrincipal("user-1", Role.TENANT))
    }
}
