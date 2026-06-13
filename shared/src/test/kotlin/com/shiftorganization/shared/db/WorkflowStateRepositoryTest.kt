package com.shiftorganization.shared.db

import com.shiftorganization.shared.domain.WorkflowState
import com.shiftorganization.shared.domain.WorkflowStatus
import com.shiftorganization.shared.domain.WorkflowStep
import com.shiftorganization.shared.domain.WorkflowType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable
import software.amazon.awssdk.enhanced.dynamodb.model.Page
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest
import java.util.*

class WorkflowStateRepositoryTest {

    private val enhancedClient = mock<DynamoDbEnhancedClient>()
    private val table = mock<DynamoDbTable<WorkflowState>>()
    private val tableName = "workflow_state"

    init {
        whenever(
            enhancedClient.table(
                eq(tableName),
                any<software.amazon.awssdk.enhanced.dynamodb.TableSchema<WorkflowState>>()
            )
        )
            .thenReturn(table)
    }

    @Test
    fun `start persists a new in-progress workflow`() {
        val repository = WorkflowStateRepository(enhancedClient, tableName)

        val workflowId = repository.start(WorkflowType.BOOKING_CREATION)

        val captor = argumentCaptor<WorkflowState>()
        verify(table).putItem(captor.capture())

        val persisted = captor.firstValue
        assertEquals(workflowId, persisted.workflowId)
        assertEquals(WorkflowType.BOOKING_CREATION.name, persisted.workflowType)
        assertEquals(WorkflowStatus.IN_PROGRESS.name, persisted.status)
        assertEquals(WorkflowStep.STARTED.name, persisted.currentStep)
        assertEquals(0, persisted.version)
        assertNotNull(persisted.createdAt)
        assertNotNull(persisted.updatedAt)
    }

    @Test
    fun `advance increments version and updates the current step`() {
        val workflowId = UUID.randomUUID().toString()
        val repository = WorkflowStateRepository(enhancedClient, tableName)
        stubExistingWorkflow(
            WorkflowState(
                workflowId = workflowId,
                workflowType = WorkflowType.BOOKING_CREATION.name,
                status = WorkflowStatus.IN_PROGRESS.name,
                currentStep = WorkflowStep.STARTED.name,
                version = 2,
                createdAt = "2026-06-04T00:00:00Z",
                updatedAt = "2026-06-04T00:00:00Z"
            )
        )

        repository.advance(workflowId, WorkflowStep.DYNAMO_WRITTEN)

        val captor = argumentCaptor<UpdateItemEnhancedRequest<WorkflowState>>()
        verify(table).updateItem(captor.capture())

        val updated = captor.firstValue.item()
        assertEquals(workflowId, updated.workflowId)
        assertEquals(WorkflowStep.DYNAMO_WRITTEN.name, updated.currentStep)
        assertEquals(3, updated.version)
    }

    @Test
    fun `complete marks the workflow as completed and sets ttl`() {
        val workflowId = UUID.randomUUID().toString()
        val repository = WorkflowStateRepository(enhancedClient, tableName)
        stubExistingWorkflow(
            WorkflowState(
                workflowId = workflowId,
                workflowType = WorkflowType.RECURRING_EVENT_REGISTRATION.name,
                status = WorkflowStatus.IN_PROGRESS.name,
                currentStep = WorkflowStep.DYNAMO_WRITTEN.name,
                version = 1,
                createdAt = "2026-06-04T00:00:00Z",
                updatedAt = "2026-06-04T00:00:00Z"
            )
        )

        repository.complete(workflowId, "eventId=evt-1")

        val captor = argumentCaptor<UpdateItemEnhancedRequest<WorkflowState>>()
        verify(table).updateItem(captor.capture())

        val updated = captor.firstValue.item()
        assertEquals(WorkflowStatus.COMPLETED.name, updated.status)
        assertEquals(WorkflowStep.COMPLETED.name, updated.currentStep)
        assertEquals("eventId=evt-1", updated.errorDetails)
        assertEquals(2, updated.version)
        assertNotNull(updated.ttl)
    }

    @Test
    fun `fail marks the workflow as failed and preserves error details`() {
        val workflowId = UUID.randomUUID().toString()
        val repository = WorkflowStateRepository(enhancedClient, tableName)
        stubExistingWorkflow(
            WorkflowState(
                workflowId = workflowId,
                workflowType = WorkflowType.RECURRING_EVENT_REGISTRATION.name,
                status = WorkflowStatus.IN_PROGRESS.name,
                currentStep = WorkflowStep.DYNAMO_WRITTEN.name,
                version = 5,
                createdAt = "2026-06-04T00:00:00Z",
                updatedAt = "2026-06-04T00:00:00Z"
            )
        )

        repository.fail(workflowId, "EventBridge failure")

        val captor = argumentCaptor<UpdateItemEnhancedRequest<WorkflowState>>()
        verify(table).updateItem(captor.capture())

        val updated = captor.firstValue.item()
        assertEquals(WorkflowStatus.FAILED.name, updated.status)
        assertEquals(WorkflowStep.FAILED.name, updated.currentStep)
        assertEquals("EventBridge failure", updated.errorDetails)
        assertEquals(6, updated.version)
        assertNotNull(updated.ttl)
    }

    private fun stubExistingWorkflow(state: WorkflowState) {
        val pageIterable = PageIterable.create(
            object : software.amazon.awssdk.core.pagination.sync.SdkIterable<Page<WorkflowState>> {
                override fun iterator(): MutableIterator<Page<WorkflowState>> =
                    mutableListOf(Page.builder(WorkflowState::class.java).items(listOf(state)).build()).iterator()
            }
        )
        whenever(table.query(any<QueryConditional>())).thenReturn(pageIterable)
    }
}
