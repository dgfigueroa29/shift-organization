package com.shiftorganization.shared.notification

import com.shiftorganization.shared.domain.Booking
import com.shiftorganization.shared.model.NotificationPayload
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishRequest
import java.time.LocalDate
import java.time.OffsetDateTime

class SnsPublisherTest {

    @Test
    fun `publishBookingCreated sends a structured JSON notification payload`() {
        val snsClient = mock<SnsClient>()
        val publisher = SnsPublisher(snsClient, "arn:aws:sns:us-east-1:123456789012:notifications")

        publisher.publishBookingCreated(sampleBooking())

        val captor = argumentCaptor<PublishRequest>()
        verify(snsClient).publish(captor.capture())
        val request = captor.firstValue
        assertEquals("arn:aws:sns:us-east-1:123456789012:notifications", request.topicArn())

        val payload = Json.decodeFromString(NotificationPayload.serializer(), request.message())
        assertEquals("BOOKING_CREATED", payload.eventType)
        assertEquals("booking-1", payload.entityId)
        assertEquals(listOf("tenant-1"), payload.affectedUserIds)
        assertNotNull(payload.timestamp)
    }

    private fun sampleBooking() = Booking(
        id = "booking-1",
        propertyId = "property-1",
        tenantId = "tenant-1",
        startDate = LocalDate.parse("2026-07-01"),
        endDate = LocalDate.parse("2026-07-05"),
        status = "confirmed",
        createdAt = OffsetDateTime.parse("2026-06-01T00:00:00Z"),
        updatedAt = OffsetDateTime.parse("2026-06-01T00:00:00Z")
    )
}
