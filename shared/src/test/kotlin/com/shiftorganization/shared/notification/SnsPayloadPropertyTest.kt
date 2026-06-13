package com.shiftorganization.shared.notification

import com.shiftorganization.shared.domain.Booking
import com.shiftorganization.shared.domain.RecurringEvent
import com.shiftorganization.shared.model.NotificationPayload
import kotlinx.serialization.json.Json
import net.jqwik.api.*
import net.jqwik.api.constraints.AlphaChars
import net.jqwik.api.constraints.StringLength
import net.jqwik.api.lifecycle.BeforeTry
import org.junit.jupiter.api.Assertions.*
import org.mockito.kotlin.*
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishRequest
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Property 19: SES delivery failures are silent to callers.
 *
 * For any notification dispatch where SES returns a delivery error, the enclosing
 * operation SHALL complete without propagating an exception to the HTTP caller,
 * and a log entry containing the entity ID and error code SHALL be emitted.
 *
 * Property 20: SNS message payload is valid structured JSON.
 *
 * For any notification event (booking created, booking cancelled, recurring event
 * triggered), the SNS message body SHALL be deserializable as a NotificationPayload
 * and SHALL contain non-null values for eventType, entityId, timestamp, and
 * affectedUserIds.
 *
 * Validates: Requirements 8.5, 8.6
 *
 * Feature: shift-organization-mvp, Property 19 & 20: SNS payload validity and SES swallowing
 */
@Label("Feature: shift-organization-mvp, Property 19 & 20: SNS payload validity and SES swallowing")
class SnsPayloadPropertyTest {

    // ---------------------------------------------------------------------------
    // Property 20 — SNS payload shape invariant
    // ---------------------------------------------------------------------------

    private lateinit var snsClient: SnsClient
    private lateinit var capturedMessages: MutableList<String>

    @BeforeTry
    fun setUp() {
        capturedMessages = mutableListOf()
        snsClient = mock {
            on { publish(any<PublishRequest>()) } doAnswer { invocation ->
                capturedMessages.add(invocation.getArgument<PublishRequest>(0).message())
                mock()
            }
        }
    }

    @Property(tries = 100)
    fun `publishBookingCreated produces a valid deserializable NotificationPayload`(
        @ForAll @AlphaChars @StringLength(min = 1, max = 36) bookingId: String,
        @ForAll @AlphaChars @StringLength(min = 1, max = 36) tenantId: String
    ) {
        val publisher = SnsPublisher(snsClient, "arn:aws:sns:us-east-1:123456789:test-topic")
        publisher.publishBookingCreated(sampleBooking(bookingId, tenantId))

        assertEquals(1, capturedMessages.size)
        val payload = parsePayload(capturedMessages[0])

        assertEquals("BOOKING_CREATED", payload.eventType)
        assertEquals(bookingId, payload.entityId)
        assertTrue(payload.timestamp.isNotBlank(), "timestamp must be non-blank")
        assertFalse(payload.affectedUserIds.isEmpty(), "affectedUserIds must contain at least one entry")
        assertTrue(payload.affectedUserIds.contains(tenantId))
    }

    @Property(tries = 100)
    fun `publishBookingCancelled produces a valid deserializable NotificationPayload`(
        @ForAll @AlphaChars @StringLength(min = 1, max = 36) bookingId: String,
        @ForAll @AlphaChars @StringLength(min = 1, max = 36) tenantId: String
    ) {
        val publisher = SnsPublisher(snsClient, "arn:aws:sns:us-east-1:123456789:test-topic")
        publisher.publishBookingCancelled(sampleBooking(bookingId, tenantId).copy(status = "cancelled"))

        val payload = parsePayload(capturedMessages[0])

        assertEquals("BOOKING_CANCELLED", payload.eventType)
        assertEquals(bookingId, payload.entityId)
        assertTrue(payload.timestamp.isNotBlank())
        assertTrue(payload.affectedUserIds.contains(tenantId))
    }

    @Property(tries = 100)
    fun `publishRecurringEventTriggered produces a valid deserializable NotificationPayload`(
        @ForAll @AlphaChars @StringLength(min = 1, max = 36) eventId: String,
        @ForAll @AlphaChars @StringLength(min = 1, max = 36) propertyId: String
    ) {
        val publisher = SnsPublisher(snsClient, "arn:aws:sns:us-east-1:123456789:test-topic")
        publisher.publishRecurringEventTriggered(sampleEvent(eventId, propertyId))

        val payload = parsePayload(capturedMessages[0])

        assertEquals("RECURRING_EVENT_TRIGGERED", payload.eventType)
        assertEquals(eventId, payload.entityId)
        assertTrue(payload.timestamp.isNotBlank())
        assertNotNull(payload.affectedUserIds)
    }

    // ---------------------------------------------------------------------------
    // Property 19 — SES delivery failures are swallowed
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `handleMessage does not propagate SES exceptions to the caller`(
        @ForAll @AlphaChars @StringLength(min = 1, max = 36) entityId: String,
        @ForAll @AlphaChars @StringLength(min = 1, max = 36) userId: String
    ) {
        val sesClient = mock<software.amazon.awssdk.services.ses.SesClient> {
            on { sendEmail(any<software.amazon.awssdk.services.ses.model.SendEmailRequest>()) } doThrow
                software.amazon.awssdk.services.ses.model.MessageRejectedException.builder()
                    .message("rejected")
                    .build()
        }
        val cognitoClient = mock<software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient> {
            on { adminGetUser(any<software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest>()) } doReturn
                cognitoUserResponse("$userId@example.com")
        }
        val service = NotificationService(
            sesClient = sesClient,
            cognitoClient = cognitoClient,
            userPoolId = "pool-test",
            senderAddress = "noreply@example.com"
        )

        // Must not throw even though SES rejects every send
        service.handleMessage(
            Json.encodeToString(
                NotificationPayload.serializer(),
                NotificationPayload(
                    eventType = "BOOKING_CREATED",
                    entityId = entityId,
                    timestamp = "2026-06-09T10:15:30Z",
                    affectedUserIds = listOf(userId)
                )
            )
        )
        // No assertion needed — the test fails if an exception propagates
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun parsePayload(json: String): NotificationPayload =
        Json.decodeFromString(NotificationPayload.serializer(), json)

    private fun sampleBooking(id: String, tenantId: String) = Booking(
        id = id,
        propertyId = "property-1",
        tenantId = tenantId,
        startDate = LocalDate.parse("2026-07-01"),
        endDate = LocalDate.parse("2026-07-05"),
        status = "confirmed",
        createdAt = OffsetDateTime.parse("2026-06-01T00:00:00Z"),
        updatedAt = OffsetDateTime.parse("2026-06-01T00:00:00Z")
    )

    private fun sampleEvent(eventId: String, propertyId: String) = RecurringEvent(
        propertyId = propertyId,
        eventId = eventId,
        cronExpression = "cron(0 9 ? * MON *)",
        eventType = "MAINTENANCE",
        status = "active",
        lastTriggeredAt = null,
        createdAt = "2026-06-01T00:00:00Z"
    )

    private fun cognitoUserResponse(email: String) =
        software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse.builder()
            .userAttributes(
                software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType.builder()
                    .name("email").value(email).build()
            )
            .build()
}
