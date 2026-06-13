package com.shiftorganization.shared.service

import com.shiftorganization.shared.config.EnvironmentConfig
import com.shiftorganization.shared.db.PropertyRepository
import com.shiftorganization.shared.db.RecurringEventRepository
import com.shiftorganization.shared.db.WorkflowStateRepository
import com.shiftorganization.shared.domain.RecurringEvent
import com.shiftorganization.shared.domain.Role
import com.shiftorganization.shared.domain.UserPrincipal
import com.shiftorganization.shared.domain.WorkflowStep
import com.shiftorganization.shared.domain.WorkflowType
import com.shiftorganization.shared.exception.BadRequestException
import com.shiftorganization.shared.exception.ForbiddenException
import com.shiftorganization.shared.exception.InternalServerException
import com.shiftorganization.shared.exception.NotFoundException
import com.shiftorganization.shared.model.CreateRecurringEventRequest
import com.shiftorganization.shared.observability.CloudWatchMetricEmitter
import com.shiftorganization.shared.scheduling.RecurringEventScheduler
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.*
import java.util.regex.Pattern

/**
 * Application service for recurring event registration and deactivation.
 *
 * Creation is a two-phase write: the [RecurringEvent] is persisted to DynamoDB
 * first, then the EventBridge cron rule is registered via [scheduler]. If the
 * EventBridge step fails after the DynamoDB record has been written, the
 * DynamoDB record is deleted (compensating action) so the system never leaves
 * an orphan record without a corresponding cron rule. Every state transition
 * is tracked in [workflowRepo].
 *
 * Deletion follows the inverse order: the cron rule is disabled in EventBridge
 * first, then the DynamoDB record is marked `inactive`. A scheduler failure
 * during deletion is logged but does not prevent the DynamoDB transition,
 * because the caller's goal is to stop the event from triggering — leaving
 * the DynamoDB record in `active` state would mislead future readers.
 */
class RecurringEventService(
    private val recurringEventRepo: RecurringEventRepository,
    private val propertyRepo: PropertyRepository,
    private val workflowRepo: WorkflowStateRepository,
    private val scheduler: RecurringEventScheduler,
    private val clock: Clock = Clock.systemUTC(),
    private val eventIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val metricEmitter: CloudWatchMetricEmitter? = null
) {
    private val logger = LoggerFactory.getLogger(RecurringEventService::class.java)

    // Basic cron expression validation - allows any non-empty string with 6-7 fields
    // This is a simplified check; EventBridge will do the actual validation
    private val CRON_PATTERN = Pattern.compile(
        "^\\S+(\\s+\\S+){5,6}$"
    )

    fun create(command: CreateRecurringEventRequest, principal: UserPrincipal): RecurringEvent {
        validateCreateCommand(command)

        val record = RecurringEvent(
            propertyId = command.propertyId,
            eventId = eventIdFactory(),
            cronExpression = command.cronExpression,
            eventType = command.eventType,
            status = "active",
            lastTriggeredAt = null,
            createdAt = clock.instant().toString()
        )

        val workflowId = workflowRepo.start(WorkflowType.RECURRING_EVENT_REGISTRATION)

        try {
            recurringEventRepo.put(record)
        } catch (e: Exception) {
            runCatching { workflowRepo.fail(workflowId, "Dynamo write failure: ${e.message}") }
                .onFailure { f -> logger.error("Workflow fail failed for {}: {}", workflowId, f.message) }
            throw InternalServerException("Failed to persist recurring event", e)
        }
        runCatching { workflowRepo.advance(workflowId, WorkflowStep.DYNAMO_WRITTEN) }
            .onFailure { f -> logger.error("Workflow advance failed for {}: {}", workflowId, f.message) }

        return try {
            scheduler.registerRule(record)
            runCatching { workflowRepo.advance(workflowId, WorkflowStep.EVENTBRIDGE_REGISTERED) }
                .onFailure { f -> logger.error("Workflow advance failed for {}: {}", workflowId, f.message) }
            runCatching { workflowRepo.complete(workflowId, "eventId=${record.eventId}") }
                .onFailure { f -> logger.error("Workflow complete failed for {}: {}", workflowId, f.message) }
            metricEmitter?.increment("recurring_event.created", mapOf("propertyId" to record.propertyId))
            record
        } catch (e: Exception) {
            runCatching { recurringEventRepo.delete(record.propertyId, record.eventId) }
                .onFailure {
                    logger.error(
                        "Compensating delete failed for {}: {}",
                        record.eventId,
                        it.message
                    )
                }
            runCatching { workflowRepo.fail(workflowId, "EventBridge failure: ${e.message}") }
                .onFailure { f -> logger.error("Workflow fail failed for {}: {}", workflowId, f.message) }
            throw InternalServerException("Failed to register EventBridge rule", e)
        }
    }

    fun delete(propertyId: String, eventId: String, principal: UserPrincipal) {
        // Verify ownership before deletion
        if (principal.role != Role.ADMIN) {
            val property = propertyRepo.findById(propertyId)
                ?: throw NotFoundException(propertyId, "Property")
            if (property.ownerId != principal.userId) {
                throw ForbiddenException("Only the property owner or an admin can delete recurring events")
            }
        }

        runCatching { scheduler.disableRule(propertyId, eventId) }
            .onFailure {
                logger.warn(
                    "EventBridge disable failed for {}/{}: {}",
                    propertyId,
                    eventId,
                    it.message
                )
            }
        recurringEventRepo.setInactive(propertyId, eventId)
        metricEmitter?.increment("recurring_event.deleted", mapOf("propertyId" to propertyId))
    }

    private fun validateCreateCommand(command: CreateRecurringEventRequest) {
        val config = EnvironmentConfig()

        // Validate propertyId is valid UUID
        runCatching { java.util.UUID.fromString(command.propertyId) }
            .onFailure { throw BadRequestException("propertyId must be a valid UUID") }

        // Validate cron expression format
        if (!CRON_PATTERN.matcher(command.cronExpression).matches()) {
            throw BadRequestException("cronExpression must be a valid Quartz cron expression (6 or 7 fields)")
        }

        // Validate eventType is known
        val validEventTypes = setOf("MAINTENANCE", "INSPECTION", "CLEANING", "CUSTOM")
        if (command.eventType !in validEventTypes) {
            throw BadRequestException("eventType must be one of: ${validEventTypes.joinToString()}")
        }
    }
}