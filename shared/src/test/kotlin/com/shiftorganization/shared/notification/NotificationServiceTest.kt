package com.shiftorganization.shared.notification

import com.shiftorganization.shared.model.NotificationPayload
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model.MessageRejectedException
import software.amazon.awssdk.services.ses.model.SendEmailRequest

class NotificationServiceTest {

    @Test
    fun `handleMessage resolves user emails and sends SES email`() {
        val sesClient = mock<SesClient>()
        val cognitoClient = mock<CognitoIdentityProviderClient>()
        whenever(cognitoClient.adminGetUser(any<AdminGetUserRequest>())).thenReturn(
            userResponse("tenant@example.com")
        )
        val service = NotificationService(
            sesClient = sesClient,
            cognitoClient = cognitoClient,
            userPoolId = "pool-1",
            senderAddress = "noreply@example.com"
        )

        service.handleMessage(message())

        val captor = argumentCaptor<SendEmailRequest>()
        verify(sesClient).sendEmail(captor.capture())
        assertEquals("noreply@example.com", captor.firstValue.source())
        assertEquals(listOf("tenant@example.com"), captor.firstValue.destination().toAddresses())
    }

    @Test
    fun `handleMessage swallows SES delivery failures`() {
        val sesClient = mock<SesClient> {
            on { sendEmail(any<SendEmailRequest>()) } doThrow MessageRejectedException.builder()
                .message("rejected")
                .build()
        }
        val cognitoClient = mock<CognitoIdentityProviderClient>()
        whenever(cognitoClient.adminGetUser(any<AdminGetUserRequest>())).thenReturn(
            userResponse("tenant@example.com")
        )
        val service = NotificationService(
            sesClient = sesClient,
            cognitoClient = cognitoClient,
            userPoolId = "pool-1",
            senderAddress = "noreply@example.com"
        )

        service.handleMessage(message())

        verify(sesClient).sendEmail(any<SendEmailRequest>())
    }

    private fun userResponse(email: String): AdminGetUserResponse =
        AdminGetUserResponse.builder()
            .userAttributes(AttributeType.builder().name("email").value(email).build())
            .build()

    private fun message(): String =
        Json.encodeToString(
            NotificationPayload.serializer(),
            NotificationPayload(
                eventType = "BOOKING_CREATED",
                entityId = "booking-1",
                timestamp = "2026-06-09T10:15:30Z",
                affectedUserIds = listOf("tenant-1")
            )
        )
}
