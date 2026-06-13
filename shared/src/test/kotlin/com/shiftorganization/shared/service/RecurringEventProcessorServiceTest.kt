package com.shiftorganization.shared.service

import com.shiftorganization.shared.db.RecurringEventRepository
import com.shiftorganization.shared.domain.RecurringEvent
import com.shiftorganization.shared.exception.NotFoundException
import com.shiftorganization.shared.notification.NotificationPublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RecurringEventProcessorServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-09T10:15:30Z"), ZoneOffset.UTC)

    @Test
    fun `process marks the recurring event as triggered and publishes a notification`() {
        val repo = mock<RecurringEventRepository>()
        val publisher = mock<NotificationPublisher>()
        val event = sampleEvent()
        whenever(repo.findByEventId("event-1")).thenReturn(event)
        val service = RecurringEventProcessorService(repo, publisher, clock)

        val result = service.process("event-1")

        assertEquals("2026-06-09T10:15:30Z", result.lastTriggeredAt)
        verify(repo).markTriggered("property-1", "event-1", "2026-06-09T10:15:30Z")
        verify(publisher).publishRecurringEventTriggered(result)
    }

    @Test
    fun `process throws NotFoundException when the recurring event does not exist`() {
        val repo = mock<RecurringEventRepository>()
        val publisher = mock<NotificationPublisher>()
        whenever(repo.findByEventId("missing")).thenReturn(null)
        val service = RecurringEventProcessorService(repo, publisher, clock)

        assertThrows<NotFoundException> {
            service.process("missing")
        }

        verify(repo, never()).markTriggered(any(), any(), any())
        verify(publisher, never()).publishRecurringEventTriggered(any())
    }

    private fun sampleEvent() = RecurringEvent(
        propertyId = "property-1",
        eventId = "event-1",
        cronExpression = "cron(0 9 ? * MON *)",
        eventType = "MAINTENANCE",
        status = "active",
        lastTriggeredAt = null,
        createdAt = "2026-06-01T00:00:00Z"
    )
}
