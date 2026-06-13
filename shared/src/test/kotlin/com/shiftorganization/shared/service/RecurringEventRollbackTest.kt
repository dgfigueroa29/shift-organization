package com.shiftorganization.shared.service

import com.shiftorganization.shared.db.PropertyRepository
import com.shiftorganization.shared.db.RecurringEventRepository
import com.shiftorganization.shared.db.WorkflowStateRepository
import com.shiftorganization.shared.domain.Role
import com.shiftorganization.shared.domain.UserPrincipal
import com.shiftorganization.shared.exception.InternalServerException
import com.shiftorganization.shared.model.CreateRecurringEventRequest
import com.shiftorganization.shared.scheduling.RecurringEventScheduler
import net.jqwik.api.*
import net.jqwik.api.constraints.AlphaChars
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.StringLength
import net.jqwik.api.lifecycle.BeforeTry
import org.junit.jupiter.api.Assertions.fail
import org.mockito.kotlin.*

/**
 * Property 16: Recurring event rollback on EventBridge failure.
 *
 * For any `POST /recurring-events` request where the EventBridge rule registration
 * fails, the system SHALL NOT leave a DynamoDB record for that recurring event,
 * and SHALL return HTTP 500 (InternalServerException).
 *
 * Validates: Requirement 6.3
 *
 * Feature: shift-organization-mvp, Property 16: Recurring event rollback on EventBridge failure
 */
@Label("Feature: shift-organization-mvp, Property 16: Recurring event rollback on EventBridge failure")
class RecurringEventRollbackTest {

    private lateinit var repo: RecurringEventRepository
    private lateinit var propertyRepo: PropertyRepository
    private lateinit var workflowRepo: WorkflowStateRepository
    private lateinit var scheduler: RecurringEventScheduler
    private var capturedEventId: String? = null

    @BeforeTry
    fun setUp() {
        capturedEventId = null
        repo = mock {
            on { put(any()) } doAnswer { invocation ->
                val event = invocation.getArgument<com.shiftorganization.shared.domain.RecurringEvent>(0)
                capturedEventId = event.eventId
                event
            }
        }
        propertyRepo = mock()
        workflowRepo = mock {
            on { start(any()) } doReturn "wf-rollback"
        }
        scheduler = mock {
            on { registerRule(any()) } doThrow RuntimeException("EventBridge unavailable")
        }
    }

    @Property(tries = 100)
    fun `EventBridge failure triggers DynamoDB compensating delete and throws InternalServerException`(
        @ForAll @IntRange(min = 0, max = 3) eventTypeIndex: Int
    ) {
        val eventTypes = listOf("MAINTENANCE", "INSPECTION", "CLEANING", "CUSTOM")
        val propertyId = "550e8400-e29b-41d4-a716-446655440000"
        val command = CreateRecurringEventRequest(
            propertyId = propertyId,
            cronExpression = "0 0 9 ? * MON *",
            eventType = eventTypes[eventTypeIndex]
        )
        val service = RecurringEventService(
            recurringEventRepo = repo,
            propertyRepo = propertyRepo,
            workflowRepo = workflowRepo,
            scheduler = scheduler
        )
        val principal = UserPrincipal("owner-1", Role.OWNER)

        try {
            service.create(command, principal)
            fail("Expected InternalServerException was not thrown")
        } catch (e: InternalServerException) {
            // expected — verify compensating action was taken
        }

        val storedEventId = capturedEventId
            ?: fail("DynamoDB put was never called — service should attempt to write before calling EventBridge")

        // The compensating delete must have been called with the same propertyId and eventId
        verify(repo).delete(propertyId, storedEventId)

        // Workflow must have been marked as failed
        verify(workflowRepo).fail(eq("wf-rollback"), any())
    }

    @Property(tries = 100)
    fun `no DynamoDB record survives when EventBridge fails`(
        @ForAll @IntRange(min = 0, max = 3) eventTypeIndex: Int
    ) {
        val eventTypes = listOf("MAINTENANCE", "INSPECTION", "CLEANING", "CUSTOM")
        val propertyId = "550e8400-e29b-41d4-a716-446655440000"
        val command = CreateRecurringEventRequest(
            propertyId = propertyId,
            cronExpression = "0 0 0 * * ?",
            eventType = eventTypes[eventTypeIndex]
        )
        val service = RecurringEventService(
            recurringEventRepo = repo,
            propertyRepo = propertyRepo,
            workflowRepo = workflowRepo,
            scheduler = scheduler
        )

        try {
            service.create(command, UserPrincipal("owner-1", Role.OWNER))
        } catch (_: InternalServerException) { }

        // put was called once, delete was called once with matching key — net effect is zero records
        verify(repo, times(1)).put(any())
        verify(repo, times(1)).delete(eq(propertyId), any())
    }
}