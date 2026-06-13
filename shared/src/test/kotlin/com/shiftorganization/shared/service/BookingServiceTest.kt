package com.shiftorganization.shared.service

import com.shiftorganization.shared.db.*
import com.shiftorganization.shared.domain.*
import com.shiftorganization.shared.exception.CancellationDeadlineException
import com.shiftorganization.shared.exception.ConflictException
import com.shiftorganization.shared.exception.ForbiddenException
import com.shiftorganization.shared.exception.NotFoundException
import com.shiftorganization.shared.model.CreateBookingRequest
import com.shiftorganization.shared.model.CreatePropertyRequest
import com.shiftorganization.shared.notification.NotificationPublisher
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.*

class BookingServiceTest {

    private class RecordingPublisher : NotificationPublisher {
        var created: Booking? = null
        var cancelled: Booking? = null
        var createdFailure: Throwable? = null
        var cancelledFailure: Throwable? = null

        override fun publishBookingCreated(booking: Booking) {
            createdFailure?.let { throw it }
            created = booking
        }

        override fun publishBookingCancelled(booking: Booking) {
            cancelledFailure?.let { throw it }
            cancelled = booking
        }

        override fun publishRecurringEventTriggered(event: RecurringEvent) = Unit
    }

    private lateinit var database: Database
    private lateinit var propertyRepo: PropertyRepository
    private lateinit var bookingRepo: BookingRepository
    private lateinit var workflowRepo: WorkflowStateRepository
    private lateinit var publisher: RecordingPublisher
    private lateinit var propertyId: String

    private val tenant = UserPrincipal("tenant-1", Role.TENANT)
    private val otherTenant = UserPrincipal("tenant-2", Role.TENANT)
    private val admin = UserPrincipal("admin-1", Role.ADMIN)

    /** A fixed "now" well before the booking date so cancellation is allowed by default. */
    private val fixedNow: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val fixedClock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            driver = "org.h2.Driver"
        )
        transaction(database) {
            SchemaUtils.create(PropertiesTable, BookingsTable)
        }
        propertyRepo = PropertyRepository(database)
        bookingRepo = BookingRepository(database)
        workflowRepo = mock()
        whenever(workflowRepo.start(any())).thenReturn("wf-1")
        publisher = RecordingPublisher()

        propertyId = propertyRepo.insert(
            CreatePropertyRequest(
                address = "123 Main St",
                description = "Apartment",
                pricePerNight = 110.0
            ),
            ownerId = "owner-1"
        ).id
    }

    private fun service(clock: Clock = fixedClock): BookingService =
        BookingService(database, bookingRepo, workflowRepo, publisher, clock)

    private fun request(
        start: String = "2026-06-01",
        end: String = "2026-06-05"
    ) = CreateBookingRequest(propertyId = propertyId, startDate = start, endDate = end)

    @Test
    fun `create persists the booking publishes the event and completes the workflow`() {
        val booking = service().create(request(), tenant.userId)

        assertEquals("confirmed", booking.status)
        assertEquals(propertyId, booking.propertyId)
        assertEquals("tenant-1", booking.tenantId)
        assertSame(booking, publisher.created)
        assertNull(publisher.cancelled)

        verify(workflowRepo).start(WorkflowType.BOOKING_CREATION)
        verify(workflowRepo).complete(eq("wf-1"), any())
        verify(workflowRepo, never()).fail(any(), any())
    }

    @Test
    fun `create throws ConflictException with every overlapping booking id and fails the workflow`() {
        val existing = service().create(request("2026-06-01", "2026-06-05"), tenant.userId)
        // Reset workflow interactions before the conflicting attempt.
        whenever(workflowRepo.start(any())).thenReturn("wf-2")

        val ex = assertThrows<ConflictException> {
            service().create(request("2026-06-03", "2026-06-10"), otherTenant.userId)
        }

        assertEquals(listOf(existing.id), ex.conflictingBookingIds)
        verify(workflowRepo).fail(eq("wf-2"), any())
    }

    @Test
    fun `create swallows notification failures and still returns the persisted booking`() {
        publisher.createdFailure = RuntimeException("SNS unavailable")

        val booking = service().create(request(), tenant.userId)

        assertNotNull(booking)
        assertEquals("confirmed", booking.status)
        verify(workflowRepo).complete(eq("wf-1"), any())
    }

    @Test
    fun `cancel marks the booking as cancelled and publishes the cancellation event`() {
        val booking = service().create(request(), tenant.userId)

        service().cancel(booking.id, tenant)

        val reloaded = bookingRepo.findById(booking.id)
        assertNotNull(reloaded)
        assertEquals("cancelled", reloaded!!.status)
        assertEquals(booking.id, publisher.cancelled?.id)
        assertEquals("cancelled", publisher.cancelled?.status)
        verify(workflowRepo).start(WorkflowType.BOOKING_CANCELLATION)
    }

    @Test
    fun `cancel rejects requests inside the 24 hour window with CancellationDeadlineException`() {
        // startDate is the same day as fixedNow — the 24h deadline has already passed.
        val startTooSoon = LocalDate.ofInstant(fixedNow, ZoneOffset.UTC)
        val booking = service().create(
            request(startTooSoon.toString(), startTooSoon.plusDays(2).toString()),
            tenant.userId
        )

        assertThrows<CancellationDeadlineException> {
            service().cancel(booking.id, tenant)
        }

        val reloaded = bookingRepo.findById(booking.id)
        assertEquals("confirmed", reloaded?.status)
        assertNull(publisher.cancelled)
        verify(workflowRepo, never()).start(WorkflowType.BOOKING_CANCELLATION)
    }

    @Test
    fun `cancel allows an admin to cancel another tenant's booking`() {
        val booking = service().create(request(), tenant.userId)

        service().cancel(booking.id, admin)

        assertEquals("cancelled", bookingRepo.findById(booking.id)?.status)
    }

    @Test
    fun `cancel rejects a different tenant with ForbiddenException`() {
        val booking = service().create(request(), tenant.userId)

        assertThrows<ForbiddenException> {
            service().cancel(booking.id, otherTenant)
        }

        assertEquals("confirmed", bookingRepo.findById(booking.id)?.status)
    }

    @Test
    fun `cancel is a no-op when the booking is already cancelled`() {
        val booking = service().create(request(), tenant.userId)
        service().cancel(booking.id, tenant)
        publisher.cancelled = null

        service().cancel(booking.id, tenant)

        assertNull(publisher.cancelled)
    }

    @Test
    fun `cancel throws NotFoundException for an unknown booking id`() {
        assertThrows<NotFoundException> {
            service().cancel(UUID.randomUUID().toString(), tenant)
        }
    }

    @Test
    fun `findById returns the booking for the owning tenant`() {
        val booking = service().create(request(), tenant.userId)

        val loaded = service().findById(booking.id, tenant)

        assertEquals(booking.id, loaded.id)
    }

    @Test
    fun `findById allows the admin role to see any booking`() {
        val booking = service().create(request(), tenant.userId)

        val loaded = service().findById(booking.id, admin)

        assertEquals(booking.id, loaded.id)
    }

    @Test
    fun `findById rejects a different tenant with ForbiddenException`() {
        val booking = service().create(request(), tenant.userId)

        assertThrows<ForbiddenException> {
            service().findById(booking.id, otherTenant)
        }
    }

    @Test
    fun `findById throws NotFoundException for an unknown booking id`() {
        assertThrows<NotFoundException> {
            service().findById(UUID.randomUUID().toString(), tenant)
        }
    }

    @Test
    fun `create fails the workflow and rethrows when the repository explodes`() {
        // Force findConflicts to blow up so the catch path is exercised.
        val brokenRepo: BookingRepository = mock {
            on { findConflicts(any(), any(), any(), any()) } doThrow RuntimeException("db down")
        }
        val brokenService = BookingService(database, brokenRepo, workflowRepo, publisher, fixedClock)

        assertThrows<com.shiftorganization.shared.exception.InternalServerException> {
            brokenService.create(request(), tenant.userId)
        }

        verify(workflowRepo).fail(eq("wf-1"), any())
        assertTrue(publisher.created == null)
    }
}
