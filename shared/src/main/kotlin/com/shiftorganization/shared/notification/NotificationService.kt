package com.shiftorganization.shared.notification

import com.shiftorganization.shared.model.NotificationPayload
import com.shiftorganization.shared.observability.CloudWatchMetricEmitter
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model.Body
import software.amazon.awssdk.services.ses.model.Content
import software.amazon.awssdk.services.ses.model.Destination
import software.amazon.awssdk.services.ses.model.Message
import software.amazon.awssdk.services.ses.model.SendEmailRequest

/**
 * Handles inbound SNS notification events and dispatches emails via SES.
 *
 * When an SNS message arrives (from the notifications Lambda SNS trigger), this
 * service deserialises the [NotificationPayload], resolves each affected user's
 * email address from Cognito, and sends a transactional email via SES.
 *
 * SES delivery failures are **intentionally swallowed** (Property 19): they are
 * logged with the entity identifier and error code but never propagate to the
 * caller. The caller's goal — handling the SNS message — is unaffected by
 * individual delivery failures.
 *
 * @param sesClient      AWS SES client used for email dispatch.
 * @param cognitoClient  AWS Cognito IDP client used to look up user email addresses.
 * @param userPoolId     The Cognito User Pool ID for `adminGetUser` calls.
 * @param senderAddress  The verified SES sender address (From: header).
 * @param metricEmitter  Optional CloudWatch metric emitter for KPI tracking.
 */
class NotificationService(
    private val sesClient: SesClient,
    private val cognitoClient: CognitoIdentityProviderClient,
    private val userPoolId: String,
    private val senderAddress: String,
    private val metricEmitter: CloudWatchMetricEmitter? = null
) {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses [rawMessage] as a [NotificationPayload], resolves the email addresses
     * of all [NotificationPayload.affectedUserIds], and dispatches one SES email
     * per address. Failures during email dispatch are logged and swallowed (Property 19).
     */
    fun handleMessage(rawMessage: String) {
        val payload = runCatching {
            json.decodeFromString(NotificationPayload.serializer(), rawMessage)
        }.onFailure { e ->
            logger.error("Failed to deserialize notification payload: {}", e.message)
            return
        }.getOrThrow()

        val emails = resolveEmails(payload.affectedUserIds)

        emails.forEach { email ->
            runCatching {
                sesClient.sendEmail(buildEmailRequest(email, payload))
            }.onFailure { e ->
                logger.error(
                    "SES delivery failure for entity {} to {}: {}",
                    payload.entityId,
                    email,
                    e.message
                )
                metricEmitter?.increment("notification.failed", mapOf(
                    "eventType" to payload.eventType,
                    "reason" to "ses_delivery_failure"
                ))
            }
        }

        metricEmitter?.increment("notification.dispatched", mapOf("eventType" to payload.eventType))
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun resolveEmails(userIds: List<String>): List<String> =
        userIds.mapNotNull { userId ->
            runCatching {
                val response = cognitoClient.adminGetUser(
                    AdminGetUserRequest.builder()
                        .userPoolId(userPoolId)
                        .username(userId)
                        .build()
                )
                response.userAttributes()
                    .firstOrNull { it.name() == "email" }
                    ?.value()
            }.onFailure { e ->
                logger.warn("Could not resolve email for user {}: {}", userId, e.message)
            }.getOrNull()
        }

    private fun buildEmailRequest(
        toAddress: String,
        payload: NotificationPayload
    ): SendEmailRequest {
        val subject = subjectFor(payload.eventType)
        val body = bodyFor(payload)

        return SendEmailRequest.builder()
            .source(senderAddress)
            .destination(
                Destination.builder().toAddresses(toAddress).build()
            )
            .message(
                Message.builder()
                    .subject(Content.builder().data(subject).charset("UTF-8").build())
                    .body(
                        Body.builder()
                            .text(Content.builder().data(body).charset("UTF-8").build())
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun subjectFor(eventType: String): String = when (eventType) {
        "BOOKING_CREATED" -> "Your booking has been confirmed"
        "BOOKING_CANCELLED" -> "Your booking has been cancelled"
        "RECURRING_EVENT_TRIGGERED" -> "A scheduled event has been triggered"
        else -> "Shift Organization notification"
    }

    private fun bodyFor(payload: NotificationPayload): String =
        "Event: ${payload.eventType}\n" +
            "Reference: ${payload.entityId}\n" +
            "Time: ${payload.timestamp}"
}