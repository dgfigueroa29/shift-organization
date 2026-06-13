package com.shiftorganization.shared.db

import com.shiftorganization.shared.domain.Property
import com.shiftorganization.shared.exception.NotFoundException
import com.shiftorganization.shared.model.CreatePropertyRequest
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.*

/**
 * PostgreSQL repository for property records.
 */
class PropertyRepository(
    private val database: Database
) {
    fun insert(command: CreatePropertyRequest, ownerId: String): Property =
        transaction(database) {
            insert(this, command, ownerId)
        }

    fun insert(transaction: Transaction, command: CreatePropertyRequest, ownerId: String): Property {
        val propertyId = UUID.randomUUID()
        val now = OffsetDateTime.now()

        PropertiesTable.insert {
            it[PropertiesTable.id] = propertyId
            it[PropertiesTable.ownerId] = ownerId
            it[PropertiesTable.address] = command.address
            it[PropertiesTable.description] = command.description
            it[PropertiesTable.priceNight] = BigDecimal.valueOf(command.pricePerNight)
            it[PropertiesTable.status] = "available"
            it[PropertiesTable.createdAt] = now
            it[PropertiesTable.updatedAt] = now
        }

        return findById(transaction, propertyId.toString())
            ?: error("Inserted property '$propertyId' could not be loaded back")
    }

    fun findById(id: String): Property? =
        transaction(database) {
            findById(this, id)
        }

    fun findById(transaction: Transaction, id: String): Property? {
        val propertyId = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        return PropertiesTable
            .selectAll()
            .where { (PropertiesTable.id eq propertyId) and (PropertiesTable.status neq "deleted") }
            .map { it.toProperty() }
            .singleOrNull()
    }

    fun update(id: String, command: CreatePropertyRequest): Property =
        transaction(database) {
            update(this, id, command)
        }

    fun update(transaction: Transaction, id: String, command: CreatePropertyRequest): Property {
        val propertyId = UUID.fromString(id)
        val now = OffsetDateTime.now()

        val affectedRows = PropertiesTable.update({ PropertiesTable.id eq propertyId }) {
            it[PropertiesTable.address] = command.address
            it[PropertiesTable.description] = command.description
            it[PropertiesTable.priceNight] = BigDecimal.valueOf(command.pricePerNight)
            it[PropertiesTable.updatedAt] = now
        }

        if (affectedRows == 0) {
            throw NotFoundException(id, "Property")
        }

        return findById(transaction, id) ?: error("Updated property '$id' could not be loaded back")
    }

    fun softDelete(id: String) =
        transaction(database) {
            softDelete(this, id)
        }

    fun softDelete(transaction: Transaction, id: String) {
        val propertyId = UUID.fromString(id)
        val now = OffsetDateTime.now()

        val affectedRows = PropertiesTable.update({ PropertiesTable.id eq propertyId }) {
            it[PropertiesTable.status] = "deleted"
            it[PropertiesTable.updatedAt] = now
        }

        if (affectedRows == 0) {
            throw NotFoundException(id, "Property")
        }
    }

    private fun ResultRow.toProperty(): Property = Property(
        id = this[PropertiesTable.id].toString(),
        ownerId = this[PropertiesTable.ownerId],
        address = this[PropertiesTable.address],
        description = this[PropertiesTable.description],
        pricePerNight = this[PropertiesTable.priceNight].toDouble(),
        status = this[PropertiesTable.status],
        createdAt = this[PropertiesTable.createdAt],
        updatedAt = this[PropertiesTable.updatedAt]
    )
}
