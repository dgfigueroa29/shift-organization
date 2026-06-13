package com.shiftorganization.shared.db

import com.shiftorganization.shared.model.CreateBookingRequest
import com.shiftorganization.shared.model.CreatePropertyRequest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.*

class BookingRepositoryTest {

    private lateinit var database: Database
    private lateinit var propertyRepository: PropertyRepository
    private lateinit var bookingRepository: BookingRepository

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            driver = "org.h2.Driver"
        )
        transaction(database) {
            SchemaUtils.create(PropertiesTable, BookingsTable)
        }
        propertyRepository = PropertyRepository(database)
        bookingRepository = BookingRepository(database)
    }

    @Test
    fun `insert and find by id returns the stored booking`() {
        val property = propertyRepository.insert(
            CreatePropertyRequest(
                address = "123 Main St",
                description = "Apartment",
                pricePerNight = 110.0
            ),
            ownerId = "owner-1"
        )

        val booking = bookingRepository.insert(
            CreateBookingRequest(
                propertyId = property.id,
                startDate = "2026-01-10",
                endDate = "2026-01-15"
            ),
            tenantId = "tenant-1"
        )

        val loaded = bookingRepository.findById(booking.id)

        assertNotNull(loaded)
        assertEquals(booking.id, loaded!!.id)
        assertEquals(property.id, loaded.propertyId)
        assertEquals("tenant-1", loaded.tenantId)
        assertEquals(LocalDate.parse("2026-01-10"), loaded.startDate)
        assertEquals(LocalDate.parse("2026-01-15"), loaded.endDate)
        assertEquals("confirmed", loaded.status)
    }

    @Test
    fun `find conflicts returns only overlapping confirmed bookings`() {
        val property = propertyRepository.insert(
            CreatePropertyRequest(
                address = "123 Main St",
                description = "Apartment",
                pricePerNight = 110.0
            ),
            ownerId = "owner-1"
        )

        val overlapping = bookingRepository.insert(
            CreateBookingRequest(
                propertyId = property.id,
                startDate = "2026-01-10",
                endDate = "2026-01-15"
            ),
            tenantId = "tenant-1"
        )

        bookingRepository.insert(
            CreateBookingRequest(
                propertyId = property.id,
                startDate = "2026-01-18",
                endDate = "2026-01-20"
            ),
            tenantId = "tenant-2"
        )

        val conflicts = bookingRepository.findConflicts(
            propertyId = property.id,
            startDate = LocalDate.parse("2026-01-12"),
            endDate = LocalDate.parse("2026-01-18")
        )

        assertEquals(1, conflicts.size)
        assertEquals(overlapping.id, conflicts.single().id)
    }

    @Test
    fun `cancel marks the booking as cancelled and removes it from conflicts`() {
        val property = propertyRepository.insert(
            CreatePropertyRequest(
                address = "123 Main St",
                description = "Apartment",
                pricePerNight = 110.0
            ),
            ownerId = "owner-1"
        )

        val booking = bookingRepository.insert(
            CreateBookingRequest(
                propertyId = property.id,
                startDate = "2026-01-10",
                endDate = "2026-01-15"
            ),
            tenantId = "tenant-1"
        )

        bookingRepository.cancel(booking.id)

        val cancelled = bookingRepository.findById(booking.id)
        assertNotNull(cancelled)
        assertEquals("cancelled", cancelled!!.status)

        val conflicts = bookingRepository.findConflicts(
            propertyId = property.id,
            startDate = LocalDate.parse("2026-01-12"),
            endDate = LocalDate.parse("2026-01-18")
        )

        assertTrue(conflicts.isEmpty())
    }
}
