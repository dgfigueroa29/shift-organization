package com.shiftorganization.shared.domain

/**
 * Lifecycle states for workflow-tracked operations.
 */
enum class WorkflowStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
