package com.shiftorganization.shared.scheduling

import com.shiftorganization.shared.domain.RecurringEvent
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.PutRuleRequest
import software.amazon.awssdk.services.eventbridge.model.DeleteRuleRequest
import software.amazon.awssdk.services.eventbridge.model.RuleState

class EventBridgeScheduler(
    private val eventBridge: EventBridgeClient,
    private val eventBusName: String
) : RecurringEventScheduler {

    private val logger = LoggerFactory.getLogger(EventBridgeScheduler::class.java)

    override fun registerRule(event: RecurringEvent) {
        eventBridge.putRule(
            PutRuleRequest.builder()
                .name("recurring-event-${event.eventId}")
                .eventBusName(eventBusName)
                .scheduleExpression(event.cronExpression)
                .state(RuleState.ENABLED)
                .build()
        )
    }

    override fun disableRule(propertyId: String, eventId: String) {
        runCatching {
            eventBridge.putRule(
                PutRuleRequest.builder()
                    .name("recurring-event-$eventId")
                    .eventBusName(eventBusName)
                    .scheduleExpression("cron(0 0 1 1 ? *)")
                    .state(RuleState.DISABLED)
                    .build()
            )
        }.onFailure { e ->
            logger.warn("Failed to disable EventBridge rule recurring-event-{}: {}", eventId, e.message)
        }
    }

    override fun deleteRule(propertyId: String, eventId: String) {
        runCatching {
            eventBridge.deleteRule(
                DeleteRuleRequest.builder()
                    .name("recurring-event-$eventId")
                    .eventBusName(eventBusName)
                    .build()
            )
        }.onFailure { e ->
            logger.warn("Failed to delete EventBridge rule recurring-event-{}: {}", eventId, e.message)
        }
    }

    companion object {
        fun create(busName: String = "default"): EventBridgeScheduler {
            return EventBridgeScheduler(
                EventBridgeClient.builder().build(),
                busName
            )
        }
    }
}
