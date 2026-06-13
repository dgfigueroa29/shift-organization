package com.shiftorganization.shared.domain

/**
 * Types of workflows tracked in DynamoDB.
 */
enum class WorkflowType {
    BOOKING_CREATION,
    BOOKING_CANCELLATION,
    RECURRING_EVENT_REGISTRATION
}
