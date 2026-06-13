package com.shiftorganization.shared.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// SNS notification payload
// ---------------------------------------------------------------------------

/**
 * Structured JSON payload published to the SNS topic for every notification event.
 *
 * @param eventType       Event discriminator, e.g. `"BOOKING_CREATED"`.
 * @param entityId        UUID of the primary entity involved (booking ID or recurring-event ID).
 * @param timestamp       ISO-8601 timestamp at which the event occurred.
 * @param affectedUserIds List of Cognito user IDs (owner + tenant) who should be notified.
 */
@Serializable
data class NotificationPayload(
    val eventType: String,
    val entityId: String,
    val timestamp: String,
    val affectedUserIds: List<String>
)
