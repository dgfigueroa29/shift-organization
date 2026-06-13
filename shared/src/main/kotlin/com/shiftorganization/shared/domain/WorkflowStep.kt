package com.shiftorganization.shared.domain

/**
 * Named workflow steps stored in DynamoDB for traceability.
 */
enum class WorkflowStep {
    STARTED,
    DYNAMO_WRITTEN,
    EVENTBRIDGE_REGISTERED,
    COMPLETED,
    FAILED
}
