package com.shiftorganization.shared.db

import com.shiftorganization.shared.domain.WorkflowState
import com.shiftorganization.shared.domain.WorkflowStatus
import com.shiftorganization.shared.domain.WorkflowStep
import com.shiftorganization.shared.domain.WorkflowType
import software.amazon.awssdk.enhanced.dynamodb.*
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * DynamoDB repository for workflow state tracking.
 */
class WorkflowStateRepository(
    private val enhancedClient: DynamoDbEnhancedClient,
    tableName: String
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(WorkflowStateRepository::class.java)

    private val table: DynamoDbTable<WorkflowState> =
        enhancedClient.table(tableName, TableSchema.fromBean(WorkflowState::class.java))

    fun start(type: WorkflowType): String {
        val workflowId = UUID.randomUUID().toString()
        val now = Instant.now()
        val record = WorkflowState(
            workflowId = workflowId,
            workflowType = type.name,
            status = WorkflowStatus.IN_PROGRESS.name,
            currentStep = WorkflowStep.STARTED.name,
            errorDetails = null,
            version = 0,
            ttl = null,
            createdAt = now.toString(),
            updatedAt = now.toString()
        )
        table.putItem(record)
        return workflowId
    }

    fun advance(workflowId: String, step: WorkflowStep) {
        updateWithVersion(workflowId) { current ->
            current.currentStep = step.name
            current.version = current.version + 1
            current.updatedAt = Instant.now().toString()
        }
    }

    fun complete(workflowId: String, details: String) {
        updateWithVersion(workflowId) { current ->
            current.status = WorkflowStatus.COMPLETED.name
            current.currentStep = WorkflowStep.COMPLETED.name
            current.errorDetails = details
            current.ttl = Instant.now().plus(7, ChronoUnit.DAYS).epochSecond
            current.version = current.version + 1
            current.updatedAt = Instant.now().toString()
        }
    }

    fun fail(workflowId: String, error: String) {
        updateWithVersion(workflowId) { current ->
            current.status = WorkflowStatus.FAILED.name
            current.currentStep = WorkflowStep.FAILED.name
            current.errorDetails = error
            current.ttl = Instant.now().plus(7, ChronoUnit.DAYS).epochSecond
            current.version = current.version + 1
            current.updatedAt = Instant.now().toString()
        }
    }

    private fun updateWithVersion(workflowId: String, updater: (WorkflowState) -> Unit) {
        val existing = findByWorkflowId(workflowId)
        if (existing == null) {
            logger.error("WorkflowState not found for workflowId={} — skipping update", workflowId)
            return
        }
        val expectedVersion = existing.version

        updater(existing)

        // NOTE: "version" is a DynamoDB reserved word; it must be aliased with an
        // expression attribute name (#v) to avoid a ValidationException at runtime.
        val expression = Expression.builder()
            .expression("#v = :expectedVersion")
            .putExpressionName("#v", "version")
            .putExpressionValue(
                ":expectedVersion",
                AttributeValue.builder().n(expectedVersion.toString()).build()
            )
            .build()

        table.updateItem(
            UpdateItemEnhancedRequest.builder(WorkflowState::class.java)
                .item(existing)
                .conditionExpression(expression)
                .build()
        )
    }

    private fun findByWorkflowId(workflowId: String): WorkflowState? =
        table.query(
            QueryConditional.keyEqualTo(
                Key.builder()
                    .partitionValue(workflowId)
                    .build()
            )
        ).flatMap { it.items() }
            .singleOrNull()
}
