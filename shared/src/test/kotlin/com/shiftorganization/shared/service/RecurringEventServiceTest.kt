package com.shiftorganization.shared.service

import com.shiftorganization.shared.db.PropertyRepository
import com.shiftorganization.shared.db.RecurringEventRepository
import com.shiftorganization.shared.db.WorkflowStateRepository
import com.shiftorganization.shared.domain.*
import com.shiftorganization.shared.exception.InternalServerException
import com.shiftorganization.shared.model.CreateRecurringEventRequest
import com.shiftorganization.shared.scheduling.RecurringEventScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RecurringEventServiceTest {

    private val fixedNow: Instant = Instant.parse("2026-06-04T12:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val owner = UserPrincipal("owner-1", Role.OWNER)

    private val command = CreateRecurringEventRequest(
        propertyId = "550e8400-e29b-41d4-a716-446655440000",
        cronExpression = "0 0 9 ? * MON *",
        eventType = "MAINTENANCE"
    )

    private fun service(
        repo: RecurringEventRepository = mock(),
        propertyRepo: PropertyRepository = mock(),
        workflow: WorkflowStateRepository = mock<WorkflowStateRepository>().also {
            whenever(it.start(any())).thenReturn("wf-1")
        },
        scheduler: RecurringEventScheduler = mock(),
        eventId: String = "evt-fixed"
    ): RecurringEventService = RecurringEventService(
        recurringEventRepo = repo,
        propertyRepo = propertyRepo,
        workflowRepo = workflow,
        scheduler = scheduler,
        clock = clock,
        eventIdFactory = { eventId }
    )

    @Test
    fun `create writes DynamoDB then schedules EventBridge and completes the workflow`() {
        val repo = mock<RecurringEventRepository>()
        whenever(repo.put(any())).thenAnswer { it.arguments[0] }
        val propertyRepo = mock<PropertyRepository>()
        val workflow = mock<WorkflowStateRepository>().also {
            whenever(it.start(any())).thenReturn("wf-1")
        }
        val scheduler = mock<RecurringEventScheduler>()
        val service = service(repo, propertyRepo, workflow, scheduler, eventId = "evt-1")

        val result = service.create(command, owner)

        assertEquals("evt-1", result.eventId)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", result.propertyId)
        assertEquals("active", result.status)
        assertEquals(fixedNow.toString(), result.createdAt)

        // Order: put → advance(DYNAMO_WRITTEN) → registerRule → advance(EVENTBRIDGE_REGISTERED) → complete
        val captor = argumentCaptor<RecurringEvent>()
        verify(repo).put(captor.capture())
        assertEquals("evt-1", captor.firstValue.eventId)
        verify(scheduler).registerRule(captor.firstValue)
        verify(workflow).start(WorkflowType.RECURRING_EVENT_REGISTRATION)
        verify(workflow).advance("wf-1", WorkflowStep.DYNAMO_WRITTEN)
        verify(workflow).advance("wf-1", WorkflowStep.EVENTBRIDGE_REGISTERED)
        verify(workflow).complete(eq("wf-1"), any())
        verify(workflow, never()).fail(any(), any())
    }

    @Test
    fun `create rolls back the DynamoDB record when EventBridge registration fails`() {
        val repo = mock<RecurringEventRepository>()
        whenever(repo.put(any())).thenAnswer { it.arguments[0] }
        val propertyRepo = mock<PropertyRepository>()
        val scheduler = mock<RecurringEventScheduler> {
            on { registerRule(any()) } doThrow RuntimeException("EventBridge down")
        }
        val workflow = mock<WorkflowStateRepository>().also {
            whenever(it.start(any())).thenReturn("wf-1")
        }
        val service = service(repo, propertyRepo, workflow, scheduler, eventId = "evt-2")

        val ex = assertThrows<InternalServerException> {
            service.create(command, owner)
        }
        assertEquals("EventBridge down", ex.cause?.message)

        verify(repo).put(any())
        verify(repo).delete("550e8400-e29b-41d4-a716-446655440000", "evt-2")
        verify(workflow).fail(eq("wf-1"), any())
    }

    @Test
    fun `create fails the workflow without scheduler call when DynamoDB write fails`() {
        val repo = mock<RecurringEventRepository> {
            on { put(any()) } doThrow RuntimeException("Dynamo throttled")
        }
        val propertyRepo = mock<PropertyRepository>()
        val scheduler = mock<RecurringEventScheduler>()
        val workflow = mock<WorkflowStateRepository>().also {
            whenever(it.start(any())).thenReturn("wf-1")
        }
        val service = service(repo, propertyRepo, workflow, scheduler, eventId = "evt-3")

        assertThrows<InternalServerException> {
            service.create(command, owner)
        }

        verify(scheduler, never()).registerRule(any())
        verify(repo, never()).delete(any(), any())
        verify(workflow).fail(eq("wf-1"), any())
    }

    @Test
    fun `delete disables the EventBridge rule and marks the DynamoDB record as inactive`() {
        val repo = mock<RecurringEventRepository>()
        val propertyRepo = mock<PropertyRepository> {
            on { findById("550e8400-e29b-41d4-a716-446655440000") } doReturn Property(
                id = "550e8400-e29b-41d4-a716-446655440000",
                ownerId = "owner-1",
                address = "Test",
                description = null,
                pricePerNight = 100.0,
                status = "available",
                createdAt = java.time.OffsetDateTime.now(),
                updatedAt = java.time.OffsetDateTime.now()
            )
        }
        val scheduler = mock<RecurringEventScheduler>()
        val service = service(repo, propertyRepo, mock<WorkflowStateRepository>(), scheduler)

        service.delete("550e8400-e29b-41d4-a716-446655440000", "evt-9", owner)

        verify(scheduler).disableRule("550e8400-e29b-41d4-a716-446655440000", "evt-9")
        verify(repo).setInactive("550e8400-e29b-41d4-a716-446655440000", "evt-9")
    }

    @Test
    fun `delete still marks the DynamoDB record as inactive when scheduler fails`() {
        val repo = mock<RecurringEventRepository>()
        val propertyRepo = mock<PropertyRepository> {
            on { findById("550e8400-e29b-41d4-a716-446655440000") } doReturn Property(
                id = "550e8400-e29b-41d4-a716-446655440000",
                ownerId = "owner-1",
                address = "Test",
                description = null,
                pricePerNight = 100.0,
                status = "available",
                createdAt = java.time.OffsetDateTime.now(),
                updatedAt = java.time.OffsetDateTime.now()
            )
        }
        val scheduler = mock<RecurringEventScheduler> {
            on { disableRule(any(), any()) } doThrow RuntimeException("eb error")
        }
        val service = service(repo, propertyRepo, mock<WorkflowStateRepository>(), scheduler)

        service.delete("550e8400-e29b-41d4-a716-446655440000", "evt-9", owner)

        verify(repo).setInactive("550e8400-e29b-41d4-a716-446655440000", "evt-9")
    }

    @Test
    fun `create completes even if workflow advance and complete calls throw`() {
        // Advance/complete are best-effort: workflow infrastructure failures must not
        // overshadow a successful DynamoDB+EventBridge registration.
        val repo = mock<RecurringEventRepository>()
        whenever(repo.put(any())).thenAnswer { it.arguments[0] }
        val propertyRepo = mock<PropertyRepository>()
        val scheduler = mock<RecurringEventScheduler>()
        val workflow = mock<WorkflowStateRepository>().also {
            whenever(it.start(any())).thenReturn("wf-1")
            whenever(it.advance(any(), any())).doThrow(RuntimeException("dynamo down"))
            whenever(it.complete(any(), any())).doThrow(RuntimeException("dynamo down"))
        }
        val service = service(repo, propertyRepo, workflow, scheduler, eventId = "evt-4")

        val result = service.create(command, owner)

        assertSame("evt-4", result.eventId)
        verify(scheduler).registerRule(any())
    }
}