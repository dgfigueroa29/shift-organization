package com.shiftorganization.shared.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Recurring-event request / response models
// ---------------------------------------------------------------------------

/**
 * Request body for `POST /recurring-events`.
 *
 * @param propertyId     UUID of the property this event belongs to (DynamoDB partition key).
 * @param cronExpression AWS EventBridge cron expression, e.g. `"cron(0 9 ? * MON *)"`.
 * @param eventType      Free-text event type label, e.g. `"MAINTENANCE"` or `"INSPECTION"`.
 */
@Serializable
data class CreateRecurringEventRequest(
    val propertyId: String,
    val cronExpression: String,
    val eventType: String
)

/**
 * Response body returned for recurring-event create and read operations.
 */
@Serializable
data class RecurringEventResponse(
    val eventId: String,
    val propertyId: String,
    val cronExpression: String,
    val eventType: String,
    /** One of: `active`, `inactive` */
    val status: String,
    val lastTriggeredAt: String?,
    val createdAt: String
)
