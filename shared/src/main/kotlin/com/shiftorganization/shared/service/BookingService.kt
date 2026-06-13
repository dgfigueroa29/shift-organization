package com.shiftorganization.shared.service

import com.shiftorganization.shared.config.EnvironmentConfig
import com.shiftorganization.shared.db.BookingRepository
import com.shiftorganization.shared.db.WorkflowStateRepository
import com.shiftorganization.shared.domain.Booking
import com.shiftorganization.shared.domain.Role
import com.shiftorganization.shared.domain.UserPrincipal
import com.shiftorganization.shared.domain.WorkflowType
import com.shiftorganization.shared.exception.*
import com.shiftorganization.shared.model.CreateBookingRequest
import com.shiftorganization.shared.notification.NotificationPublisher
import com.shiftorganization.shared.observability.CloudWatchMetricEmitter
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Application service for booking creation, cancellation, and access.
 *
 * Creation runs the conflict check and the insert atomically inside a
 * `REPEATABLE READ` transaction so that a race against another concurrent
 * booking cannot defeat the pre-check; the PostgreSQL exclusion constraint
 * (`no_overlap`) is the final guard and, when it fires, the violation is
 * translated to a [ConflictException] with every conflicting booking ID.
 *
 * Every multi-step flow (create, cancel) is mirrored in DynamoDB via the
 * [workflowRepo], and a notification is published through [notificationPublisher]
 * after the database transaction commits. Notification failures are logged but
 * never propagate to the caller.
 *
 * KPI metrics are emitted via [metricEmitter] for every terminal booking event
 * (Requirement 9.3): `booking.created`, `booking.cancelled`, `conflict.detected`.
 */
class BookingService(
    private val database: Database,
    private val bookingRepo: BookingRepository,
    private val workflowRepo: WorkflowStateRepository,
    private val notificationPublisher: NotificationPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val metricEmitter: CloudWatchMetricEmitter? = null
) {
    private val logger = LoggerFactory.getLogger(BookingService::class.java)

    fun create(command: CreateBookingRequest, tenantId: String): Booking {
        validateCreateCommand(command)

        val startDate = LocalDate.parse(command.startDate)
        val endDate   = LocalDate.parse(command.endDate)

        val workflowId = workflowRepo.start(WorkflowType.BOOKING_CREATION)

        val booking = try {
            transaction(
                transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
                db = database
            ) {
                val conflicts = bookingRepo.findConflicts(
                    this, command.propertyId, startDate, endDate
                )
                if (conflicts.isNotEmpty()) {
                    val ids = conflicts.map { it.id }
                    runCatching { workflowRepo.fail(workflowId, "Conflicts: $ids") }
                    metricEmitter?.increment(
                        METRIC_CONFLICT_DETECTED,
                        mapOf("propertyId" to command.propertyId)
                    )
                    throw ConflictException(ids)
                }

                try {
                    bookingRepo.insert(this, command, tenantId)
                } catch (e: ExposedSQLException) {
                    if (e.sqlState == EXCLUSION_VIOLATION) {
                        val raceConflicts = bookingRepo.findConflicts(
                            this, command.propertyId, startDate, endDate
                        ).map { it.id }
                        runCatching { workflowRepo.fail(workflowId, "DB overlap: $raceConflicts") }
                        metricEmitter?.increment(
                            METRIC_CONFLICT_DETECTED,
                            mapOf("propertyId" to command.propertyId)
                        )
                        throw ConflictException(raceConflicts)
                    }
                    throw e
                }
            }
        } catch (e: DomainException) {
            throw e
        } catch (e: Exception) {
            runCatching { workflowRepo.fail(workflowId, e.message ?: "unknown") }
            throw InternalServerException("Booking creation failed", e)
        }

        runCatching { workflowRepo.complete(workflowId, "bookingId=${booking.id}") }
            .onFailure { logger.warn("Workflow complete failed for ${booking.id}: ${it.message}") }
        runCatching { notificationPublisher.publishBookingCreated(booking) }
            .onFailure { logger.warn("SNS publish failed for booking ${booking.id}: ${it.message}") }
        metricEmitter?.increment(METRIC_BOOKING_CREATED, mapOf("propertyId" to booking.propertyId))

        return booking
    }

    fun cancel(id: String, principal: UserPrincipal) {
        var cancelledBooking: Booking? = null
        var workflowId: String? = null
        
        try {
            transaction(db = database) {
                val booking = bookingRepo.findById(this, id) ?: throw NotFoundException(id, "Booking")
                requireAccess(booking, principal)
                if (booking.status == "cancelled") return@transaction

                if (isPastCancellationDeadline(booking.startDate)) {
                    throw CancellationDeadlineException(id)
                }

                // Start workflow only after deadline check passes
                workflowId = workflowRepo.start(WorkflowType.BOOKING_CANCELLATION)

                val updatedRows = bookingRepo.cancelWithCheck(this, id, "confirmed")
                if (updatedRows == 0) {
                    throw ConflictException(listOf("Booking already cancelled or modified"))
                }
                cancelledBooking = booking.copy(status = "cancelled")
            }
        } catch (e: DomainException) {
            workflowId?.let { runCatching { workflowRepo.fail(it, e.message ?: "cancel failure") } }
            throw e
        } catch (e: Exception) {
            workflowId?.let { runCatching { workflowRepo.fail(it, e.message ?: "cancel failure") } }
            throw InternalServerException("Booking cancellation failed", e)
        }

        workflowId?.let { runCatching { workflowRepo.complete(it, "bookingId=$id cancelled") }
            .onFailure { logger.warn("Workflow complete failed for $id: ${it.message}") } }
        
        cancelledBooking?.let { booking ->
            runCatching {
                notificationPublisher.publishBookingCancelled(booking)
            }.onFailure { logger.warn("SNS publish failed for cancellation $id: ${it.message}") }
            metricEmitter?.increment(METRIC_BOOKING_CANCELLED, mapOf("propertyId" to booking.propertyId))
        }
    }

    fun findById(id: String, principal: UserPrincipal): Booking {
        val booking = bookingRepo.findById(id) ?: throw NotFoundException(id, "Booking")
        requireAccess(booking, principal)
        return booking
    }

    private fun requireAccess(booking: Booking, principal: UserPrincipal) {
        if (principal.role == Role.ADMIN) return
        if (booking.tenantId == principal.userId) return
        throw ForbiddenException("Only the booking tenant or an admin can access this booking")
    }

    private fun isPastCancellationDeadline(startDate: LocalDate): Boolean {
        val startInstant = startDate.atStartOfDay(ZoneOffset.UTC).toInstant()
        val deadline     = clock.instant().plus(24, ChronoUnit.HOURS)
        return deadline.isAfter(startInstant)
    }

    private fun validateCreateCommand(command: CreateBookingRequest) {
        val config = EnvironmentConfig()

        // Validate propertyId is valid UUID
        runCatching { UUID.fromString(command.propertyId) }
            .onFailure { throw BadRequestException("propertyId must be a valid UUID") }

        // Validate date formats
        val startDate = runCatching { LocalDate.parse(command.startDate) }
            .getOrElse { throw BadRequestException("startDate must be a valid ISO-8601 date (YYYY-MM-DD)") }
        val endDate = runCatching { LocalDate.parse(command.endDate) }
            .getOrElse { throw BadRequestException("endDate must be a valid ISO-8601 date (YYYY-MM-DD)") }

        // Validate date order
        if (!endDate.isAfter(startDate)) {
            throw BadRequestException("endDate must be after startDate")
        }
    }

    companion object {
        /** PostgreSQL SQLSTATE for an `EXCLUDE` constraint violation. */
        private const val EXCLUSION_VIOLATION = "23P01"

        // CloudWatch metric names (Requirement 9.3)
        const val METRIC_BOOKING_CREATED    = "booking.created"
        const val METRIC_BOOKING_CANCELLED  = "booking.cancelled"
        const val METRIC_CONFLICT_DETECTED  = "conflict.detected"
    }
}