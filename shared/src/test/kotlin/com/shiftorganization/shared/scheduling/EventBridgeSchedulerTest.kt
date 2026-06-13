package com.shiftorganization.shared.scheduling

import com.shiftorganization.shared.domain.RecurringEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.DeleteRuleRequest
import software.amazon.awssdk.services.eventbridge.model.PutRuleRequest

class EventBridgeSchedulerTest {

    private val eventBridge = mock<EventBridgeClient>()
    private val scheduler = EventBridgeScheduler(eventBridge, "default")

    @Test
    fun `registerRule puts an enabled cron rule`() {
        val event = RecurringEvent(
            propertyId = "prop-1",
            eventId = "evt-1",
            cronExpression = "cron(0 9 * * ? *)",
            eventType = "INSPECTION"
        )

        scheduler.registerRule(event)

        val captor = argumentCaptor<PutRuleRequest>()
        verify(eventBridge).putRule(captor.capture())
        val request = captor.firstValue
        assertEquals("recurring-event-evt-1", request.name())
        assertEquals("cron(0 9 * * ? *)", request.scheduleExpression())
        assertEquals("ENABLED", request.stateAsString())
    }

    @Test
    fun `disableRule puts a disabled cron rule`() {
        scheduler.disableRule("prop-1", "evt-1")

        val captor = argumentCaptor<PutRuleRequest>()
        verify(eventBridge).putRule(captor.capture())
        assertEquals("DISABLED", captor.firstValue.stateAsString())
    }

    @Test
    fun `deleteRule calls EventBridge deleteRule`() {
        scheduler.deleteRule("prop-1", "evt-1")

        val captor = argumentCaptor<DeleteRuleRequest>()
        verify(eventBridge).deleteRule(captor.capture())
        assertEquals("recurring-event-evt-1", captor.firstValue.name())
    }
}
