package com.shiftorganization.shared.service

import com.shiftorganization.shared.db.RecurringEventRepository
import com.shiftorganization.shared.domain.RecurringEvent
import com.shiftorganization.shared.exception.NotFoundException
import com.shiftorganization.shared.notification.NotificationPublisher
import com.shiftorganization.shared.observability.CloudWatchMetricEmitter
import java.time.Clock

/**
 * Handles EventBridge-triggered invocations for recurring event processing.
 *
 * When an EventBridge rule fires, this service:
 * 1. Looks up the [RecurringEvent] by its [eventId].
 * 2. Records the trigger time in DynamoDB via [RecurringEventRepository.markTriggered].
 * 3. Publishes an SNS notification via [NotificationPublisher.publishRecurringEventTriggered].
 *
 * Requirement 6.4: When an EventBridge Rule fires, the Lambda shall invoke the
 * handler with the RecurringEvent context.
 * Requirement 6.5: Update `lastTriggeredAt` in DynamoDB.
 * Requirement 8.4: Publish notification event to SNS when recurring event is triggered.
 */
class RecurringEventProcessorService(
    private val recurringEventRepo: RecurringEventRepository,
    private val notificationPublisher: NotificationPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val metricEmitter: CloudWatchMetricEmitter? = null
) {
    /**
     * Processes a single recurring event trigger identified by [eventId].
     *
     * @return the updated [RecurringEvent] with `lastTriggeredAt` set.
     * @throws NotFoundException if no event with the given [eventId] exists.
     */
    fun process(eventId: String): RecurringEvent {
        val event = recurringEventRepo.findByEventId(eventId)
            ?: throw NotFoundException(eventId, "RecurringEvent")

        val triggeredAt = clock.instant().toString()
        recurringEventRepo.markTriggered(event.propertyId, event.eventId, triggeredAt)

        val updated = event.copy(lastTriggeredAt = triggeredAt)
        notificationPublisher.publishRecurringEventTriggered(updated)
        metricEmitter?.increment("recurring_event.triggered", mapOf("eventType" to event.eventType))
        return updated
    }
}
