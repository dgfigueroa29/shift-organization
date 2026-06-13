package com.shiftorganization.shared.service

import com.shiftorganization.shared.config.EnvironmentConfig
import com.shiftorganization.shared.db.PropertyRepository
import com.shiftorganization.shared.domain.Property
import com.shiftorganization.shared.domain.Role
import com.shiftorganization.shared.domain.UserPrincipal
import com.shiftorganization.shared.exception.BadRequestException
import com.shiftorganization.shared.exception.ForbiddenException
import com.shiftorganization.shared.exception.NotFoundException
import com.shiftorganization.shared.model.CreatePropertyRequest
import com.shiftorganization.shared.observability.CloudWatchMetricEmitter
import com.shiftorganization.shared.search.OpenSearchClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID

/**
 * Application service for property CRUD.
 *
 * Persists every mutation to PostgreSQL synchronously through [propertyRepo]
 * and best-effort-mirrors the change to OpenSearch via [searchClient] on
 * [scope] using [ioDispatcher]. The OpenSearch sync runs as a fire-and-forget
 * coroutine — failures are logged and never propagate to the caller, so the
 * HTTP response is unaffected when the search cluster is degraded.
 */
class PropertyService(
    private val propertyRepo: PropertyRepository,
    private val searchClient: OpenSearchClient,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Max milliseconds to wait for OpenSearch sync before logging and moving on (Req 2.11). */
    private val syncTimeoutMs: Long = 5_000L,
    private val metricEmitter: CloudWatchMetricEmitter? = null
) {
    private val logger = LoggerFactory.getLogger(PropertyService::class.java)

    fun create(command: CreatePropertyRequest, principal: UserPrincipal): Property {
        validateCreateCommand(command)
        val property = propertyRepo.insert(command, principal.userId)
        indexAsync(property)
        metricEmitter?.increment("property.created", mapOf("propertyId" to property.id))
        return property
    }

    fun findById(id: String): Property? = propertyRepo.findById(id)

    fun update(id: String, command: CreatePropertyRequest, principal: UserPrincipal): Property {
        validateUpdateCommand(command)
        val existing = propertyRepo.findById(id) ?: throw NotFoundException(id, "Property")
        requireOwnership(existing.ownerId, principal)
        val updated = propertyRepo.update(id, command)
        indexAsync(updated)
        metricEmitter?.increment("property.updated", mapOf("propertyId" to updated.id))
        return updated
    }

    fun delete(id: String, principal: UserPrincipal) {
        val existing = propertyRepo.findById(id) ?: throw NotFoundException(id, "Property")
        requireOwnership(existing.ownerId, principal)
        propertyRepo.softDelete(id)
        deleteAsync(id)
        metricEmitter?.increment("property.deleted", mapOf("propertyId" to id))
    }

    private fun requireOwnership(ownerId: String, principal: UserPrincipal) {
        if (principal.role != Role.ADMIN && ownerId != principal.userId) {
            throw ForbiddenException(
                "Only the property owner or an admin can perform this operation"
            )
        }
    }

    private fun validateCreateCommand(command: CreatePropertyRequest) {
        if (command.address.isNullOrBlank()) {
            throw BadRequestException("address is required and cannot be empty")
        }
        if (command.pricePerNight <= 0) {
            throw BadRequestException("pricePerNight must be greater than 0")
        }
    }

    private fun validateUpdateCommand(command: CreatePropertyRequest) {
        if (command.address.isNullOrBlank()) {
            throw BadRequestException("address is required and cannot be empty")
        }
        if (command.pricePerNight <= 0) {
            throw BadRequestException("pricePerNight must be greater than 0")
        }
    }

    private fun indexAsync(property: Property) {
        scope.launch(ioDispatcher) {
            try {
                withTimeout(syncTimeoutMs) {
                    searchClient.indexProperty(property)
                }
            } catch (e: Exception) {
                logger.warn(
                    "OpenSearch index sync failed for property {}: {}",
                    property.id,
                    e.message
                )
            }
        }
    }

    private fun deleteAsync(id: String) {
        scope.launch(ioDispatcher) {
            try {
                withTimeout(syncTimeoutMs) {
                    searchClient.deleteProperty(id)
                }
            } catch (e: Exception) {
                logger.warn(
                    "OpenSearch delete sync failed for property {}: {}",
                    id,
                    e.message
                )
            }
        }
    }
}