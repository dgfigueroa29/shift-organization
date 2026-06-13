package com.shiftorganization.shared.db

import com.shiftorganization.shared.domain.Booking
import com.shiftorganization.shared.exception.NotFoundException
import com.shiftorganization.shared.model.CreateBookingRequest
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.*

/**
 * PostgreSQL repository for booking records.
 */
class BookingRepository(
    private val database: Database
) {
    fun insert(command: CreateBookingRequest, tenantId: String): Booking =
        transaction(database) {
            insert(this, command, tenantId)
        }

    fun insert(transaction: Transaction, command: CreateBookingRequest, tenantId: String): Booking {
        val bookingId = UUID.randomUUID()
        val propertyId = UUID.fromString(command.propertyId)
        val now = OffsetDateTime.now()

        BookingsTable.insert {
            it[BookingsTable.id] = bookingId
            it[BookingsTable.propertyId] = propertyId
            it[BookingsTable.tenantId] = tenantId
            it[BookingsTable.startDate] = LocalDate.parse(command.startDate)
            it[BookingsTable.endDate] = LocalDate.parse(command.endDate)
            it[BookingsTable.status] = "confirmed"
            it[BookingsTable.createdAt] = now
            it[BookingsTable.updatedAt] = now
        }

        return findById(transaction, bookingId.toString())
            ?: error("Inserted booking '$bookingId' could not be loaded back")
    }

    fun findById(id: String): Booking? =
        transaction(database) {
            findById(this, id)
        }

    fun findById(transaction: Transaction, id: String): Booking? {
        val bookingId = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        return BookingsTable
            .selectAll()
            .where { BookingsTable.id eq bookingId }
            .map { it.toBooking() }
            .singleOrNull()
    }

    fun cancel(id: String) =
        transaction(database) {
            cancel(this, id)
        }

    fun cancel(transaction: Transaction, id: String) {
        val bookingId = UUID.fromString(id)
        val now = OffsetDateTime.now()

        val affectedRows = BookingsTable.update({ BookingsTable.id eq bookingId }) {
            it[BookingsTable.status] = "cancelled"
            it[BookingsTable.updatedAt] = now
        }

        if (affectedRows == 0) {
            throw NotFoundException(id, "Booking")
        }
    }

    fun cancelWithCheck(transaction: Transaction, id: String, expectedStatus: String): Int {
        val bookingId = UUID.fromString(id)
        val now = OffsetDateTime.now()

        return BookingsTable.update({ (BookingsTable.id eq bookingId) and (BookingsTable.status eq expectedStatus) }) {
            it[BookingsTable.status] = "cancelled"
            it[BookingsTable.updatedAt] = now
        }
    }

    fun findConflicts(
        propertyId: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<Booking> =
        transaction(database) {
            findConflicts(this, propertyId, startDate, endDate)
        }

    fun findConflicts(
        transaction: Transaction,
        propertyId: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<Booking> {
        val propertyUuid = UUID.fromString(propertyId)
        return BookingsTable
            .selectAll()
            .where {
                (BookingsTable.propertyId eq propertyUuid) and
                        (BookingsTable.status eq "confirmed") and
                        (BookingsTable.startDate less endDate) and
                        (BookingsTable.endDate greater startDate)
            }
            .orderBy(BookingsTable.startDate)
            .map { it.toBooking() }
    }

    private fun ResultRow.toBooking(): Booking = Booking(
        id = this[BookingsTable.id].toString(),
        propertyId = this[BookingsTable.propertyId].toString(),
        tenantId = this[BookingsTable.tenantId],
        startDate = this[BookingsTable.startDate],
        endDate = this[BookingsTable.endDate],
        status = this[BookingsTable.status],
        createdAt = this[BookingsTable.createdAt],
        updatedAt = this[BookingsTable.updatedAt]
    )
}
