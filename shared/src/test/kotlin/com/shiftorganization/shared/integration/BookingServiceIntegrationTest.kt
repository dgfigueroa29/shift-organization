package com.shiftorganization.shared.integration

import com.shiftorganization.shared.db.BookingRepository
import com.shiftorganization.shared.db.BookingsTable
import com.shiftorganization.shared.db.PropertiesTable
import com.shiftorganization.shared.db.PropertyRepository
import com.shiftorganization.shared.db.WorkflowStateRepository
import com.shiftorganization.shared.domain.Role
import com.shiftorganization.shared.domain.UserPrincipal
import com.shiftorganization.shared.exception.CancellationDeadlineException
import com.shiftorganization.shared.exception.ConflictException
import com.shiftorganization.shared.model.CreateBookingRequest
import com.shiftorganization.shared.model.CreatePropertyRequest
import com.shiftorganization.shared.notification.NotificationPublisher
import com.shiftorganization.shared.service.BookingService
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Integration tests for [BookingService] against a real PostgreSQL instance.
 *
 * Covers:
 * - Atomic conflict check + insert at REPEATABLE READ isolation (Requirement 4.10, 5.3)
 * - Concurrent booking attempts: only one succeeds when they overlap (race condition safety)
 * - Cancellation releases the date range for new bookings (Requirement 5.5)
 * - Cancellation deadline enforcement with a real clock boundary (Requirements 4.8, 4.9)
 *
 * The `no_overlap` EXCLUDE constraint in the DDL is the final guard; the service-level
 * pre-check + the constraint together guarantee atomicity even under concurrent load.
 */
@Tag("integration")
@Testcontainers
class BookingServiceIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("shift_test")
            .withUsername("test")
            .withPassword("test")
    }

    private lateinit var database: Database
    private lateinit var propertyRepo: PropertyRepository
    private lateinit var bookingRepo: BookingRepository
    private lateinit var workflowRepo: WorkflowStateRepository
    private lateinit var publisher: NotificationPublisher
    private lateinit var propertyId: String

    private val tenant1 = UserPrincipal("tenant-1", Role.TENANT)
    private val tenant2 = UserPrincipal("tenant-2", Role.TENANT)
    private val admin   = UserPrincipal("admin-1",  Role.ADMIN)

    // Fixed clock well before any booking date — cancellation is always allowed unless overridden
    private val pastClock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            url      = postgres.jdbcUrl,
            driver   = "org.postgresql.Driver",
            user     = postgres.username,
            password = postgres.password
        )
        transaction(database) {
            // Use Exposed DDL — the EXCLUDE constraint is added manually below
            SchemaUtils.create(PropertiesTable, BookingsTable)
            // Add the exclusion constraint that enforces no-overlap at the DB level
            exec(
                """
                DO ${'$'}${'$'}
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_constraint WHERE conname = 'no_overlap'
                    ) THEN
                        ALTER TABLE bookings
                        ADD CONSTRAINT no_overlap
                        EXCLUDE USING gist (
                            property_id WITH =,
                            daterange(start_date, end_date, '[)') WITH &&
                        )
                        WHERE (status = 'confirmed');
                    END IF;
                END;
                ${'$'}${'$'};
                """.trimIndent()
            )
        }
        propertyRepo = PropertyRepository(database)
        bookingRepo  = BookingRepository(database)
        workflowRepo = mock { on { start(any()) } doReturn "wf-1" }
        publisher    = mock()
        propertyId   = propertyRepo.insert(
            CreatePropertyRequest("10 Integration Ave", "Test property", 150.0),
            "owner-1"
        ).id
    }

    private fun service(clock: Clock = pastClock) =
        BookingService(database, bookingRepo, workflowRepo, publisher, clock)

    private fun req(start: String, end: String) =
        CreateBookingRequest(propertyId = propertyId, startDate = start, endDate = end)

    // -------------------------------------------------------------------------
    // Happy path: create and retrieve
    // -------------------------------------------------------------------------

    @Test
    fun `booking is persisted and retrievable after creation`() {
        val booking = service().create(req("2026-07-01", "2026-07-05"), tenant1.userId)

        assertNotNull(booking.id)
        assertEquals("confirmed", booking.status)
        assertEquals(propertyId, booking.propertyId)
        assertEquals(tenant1.userId, booking.tenantId)

        val loaded = bookingRepo.findById(booking.id)
        assertNotNull(loaded)
        assertEquals(booking.id, loaded!!.id)
    }

    // -------------------------------------------------------------------------
    // Conflict detection with real DB EXCLUDE constraint
    // -------------------------------------------------------------------------

    @Test
    fun `overlapping booking attempt is rejected with ConflictException`() {
        val first = service().create(req("2026-08-01", "2026-08-10"), tenant1.userId)

        val ex = assertThrows<ConflictException> {
            service().create(req("2026-08-05", "2026-08-15"), tenant2.userId)
        }

        assertTrue(ex.conflictingBookingIds.contains(first.id),
            "ConflictException must include the ID of the existing booking")
    }

    @Test
    fun `non-overlapping booking on same property succeeds`() {
        service().create(req("2026-09-01", "2026-09-05"), tenant1.userId)

        val second = service().create(req("2026-09-05", "2026-09-10"), tenant2.userId)

        assertEquals("confirmed", second.status)
    }

    // -------------------------------------------------------------------------
    // Concurrent booking: only one of two overlapping requests wins
    // -------------------------------------------------------------------------

    @Test
    fun `concurrent overlapping bookings result in exactly one confirmed booking`() {
        val executor = Executors.newFixedThreadPool(2)
        val results = mutableListOf<Future<Result<Any>>>()

        repeat(2) { i ->
            val future: Future<Result<Any>> = executor.submit(Callable {
                runCatching<Any> {
                    service().create(req("2026-10-01", "2026-10-07"), "tenant-concurrent-$i")
                }
            })
            results.add(future)
        }
        executor.shutdown()

        val outcomes = results.map { it.get() }
        val successes = outcomes.count { it.isSuccess }
        val failures  = outcomes.count { it.isFailure }

        // Exactly one should win; the other hits either the pre-check or the EXCLUDE constraint
        assertEquals(1, successes, "Exactly one concurrent booking must succeed")
        assertEquals(1, failures,  "The other concurrent booking must fail with a conflict")

        val conflictFailure = outcomes.first { it.isFailure }.exceptionOrNull()
        assertTrue(
            conflictFailure is ConflictException,
            "Failure must be a ConflictException, was: ${conflictFailure?.javaClass?.simpleName}"
        )
    }

    // -------------------------------------------------------------------------
    // Cancellation releases the date range
    // -------------------------------------------------------------------------

    @Test
    fun `cancelled booking releases the date range for a new booking`() {
        val original = service().create(req("2026-11-01", "2026-11-07"), tenant1.userId)
        service().cancel(original.id, tenant1)

        // Same dates should now be bookable
        val replacement = service().create(req("2026-11-01", "2026-11-07"), tenant2.userId)

        assertEquals("confirmed", replacement.status)
        assertEquals("cancelled", bookingRepo.findById(original.id)?.status)
    }

    // -------------------------------------------------------------------------
    // Cancellation deadline: real boundary check
    // -------------------------------------------------------------------------

    @Test
    fun `cancellation is allowed when more than 24 hours before start date`() {
        // startDate is 2026-12-01; now is 2026-11-29 — well over 24 h
        val earlyEnoughClock = Clock.fixed(
            Instant.parse("2026-11-29T00:00:00Z"), ZoneOffset.UTC
        )
        val booking = service(earlyEnoughClock).create(req("2026-12-01", "2026-12-05"), tenant1.userId)

        service(earlyEnoughClock).cancel(booking.id, tenant1)

        assertEquals("cancelled", bookingRepo.findById(booking.id)?.status)
    }

    @Test
    fun `cancellation is rejected when less than 24 hours before start date`() {
        // startDate is 2026-12-01; now is 2026-11-30T12:00 — only 12 h left
        val tooLateClock = Clock.fixed(
            Instant.parse("2026-11-30T12:00:00Z"), ZoneOffset.UTC
        )
        // Create with an earlier clock so the booking itself is valid
        val booking = service(pastClock).create(req("2026-12-01", "2026-12-05"), tenant1.userId)

        assertThrows<CancellationDeadlineException> {
            service(tooLateClock).cancel(booking.id, tenant1)
        }

        assertEquals("confirmed", bookingRepo.findById(booking.id)?.status)
    }

    // -------------------------------------------------------------------------
    // Admin cross-tenant visibility
    // -------------------------------------------------------------------------

    @Test
    fun `admin can retrieve and cancel any tenant's booking`() {
        val booking = service().create(req("2026-12-10", "2026-12-15"), tenant1.userId)

        val loaded = service().findById(booking.id, admin)
        assertEquals(booking.id, loaded.id)

        service().cancel(booking.id, admin)
        assertEquals("cancelled", bookingRepo.findById(booking.id)?.status)
    }
}
