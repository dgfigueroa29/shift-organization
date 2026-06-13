package com.shiftorganization.shared.scheduling

import com.shiftorganization.shared.domain.RecurringEvent

/**
 * Abstraction over AWS EventBridge cron-rule lifecycle for recurring events.
 *
 * Implementations wrap the AWS SDK's `EventBridgeClient` and translate calls
 * into `PutRule` / `PutTargets` / `DisableRule` operations. The service layer
 * uses this abstraction so the two-phase write that persists a recurring
 * event (DynamoDB → EventBridge) can be tested without a live AWS connection.
 */
interface RecurringEventScheduler {

    /**
     * Register or upsert an EventBridge cron rule for [event]. Implementations
     * should throw on any transport, authentication, or validation failure so
     * the calling service can compensate the prior DynamoDB write.
     */
    fun registerRule(event: RecurringEvent)

    /**
     * Disable the EventBridge cron rule identified by ([propertyId], [eventId]).
     * Implementations should be idempotent — disabling an already-disabled or
     * missing rule MUST NOT throw.
     */
    fun disableRule(propertyId: String, eventId: String)

    /**
     * Permanently delete the EventBridge cron rule identified by
     * ([propertyId], [eventId]). Used by the rollback path when the rule
     * registration succeeded but a later step in the workflow failed.
     */
    fun deleteRule(propertyId: String, eventId: String)
}
