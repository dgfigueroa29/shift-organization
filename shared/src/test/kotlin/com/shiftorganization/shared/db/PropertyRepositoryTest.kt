package com.shiftorganization.shared.db

import com.shiftorganization.shared.model.CreatePropertyRequest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class PropertyRepositoryTest {

    private lateinit var database: Database
    private lateinit var repository: PropertyRepository

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            driver = "org.h2.Driver"
        )
        transaction(database) {
            SchemaUtils.create(PropertiesTable)
        }
        repository = PropertyRepository(database)
    }

    @Test
    fun `insert and find by id returns the stored property`() {
        val property = repository.insert(
            CreatePropertyRequest(
                address = "123 Main St",
                description = "Bright apartment",
                pricePerNight = 120.50
            ),
            ownerId = "owner-1"
        )

        val loaded = repository.findById(property.id)

        assertNotNull(loaded)
        assertEquals(property.id, loaded!!.id)
        assertEquals("owner-1", loaded.ownerId)
        assertEquals("123 Main St", loaded.address)
        assertEquals("Bright apartment", loaded.description)
        assertEquals(120.50, loaded.pricePerNight)
        assertEquals("available", loaded.status)
    }

    @Test
    fun `update changes mutable fields and keeps the same id`() {
        val property = repository.insert(
            CreatePropertyRequest(
                address = "123 Main St",
                description = "Initial description",
                pricePerNight = 90.0
            ),
            ownerId = "owner-1"
        )

        val updated = repository.update(
            property.id,
            CreatePropertyRequest(
                address = "456 Elm St",
                description = "Updated description",
                pricePerNight = 135.75
            )
        )

        assertEquals(property.id, updated.id)
        assertEquals("owner-1", updated.ownerId)
        assertEquals("456 Elm St", updated.address)
        assertEquals("Updated description", updated.description)
        assertEquals(135.75, updated.pricePerNight)
        assertEquals("available", updated.status)
        assertTrue(updated.updatedAt >= property.updatedAt)
    }

    @Test
    fun `soft delete marks the property as deleted`() {
        val property = repository.insert(
            CreatePropertyRequest(
                address = "123 Main St",
                description = null,
                pricePerNight = 80.0
            ),
            ownerId = "owner-1"
        )

        repository.softDelete(property.id)

        val deleted = repository.findById(property.id)
        assertNull(deleted)
    }
}
