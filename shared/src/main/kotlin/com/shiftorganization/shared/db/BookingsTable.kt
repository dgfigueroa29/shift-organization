package com.shiftorganization.shared.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed table definition for the `bookings` relation.
 *
 * Mirrors the PostgreSQL schema used by the booking service:
 * - UUID primary key
 * - `property_id` foreign key to `properties(id)`
 * - date range columns for conflict detection
 * - status lifecycle with `confirmed` / `cancelled`
 */
object BookingsTable : Table("bookings") {

    /** UUID primary key. */
    val id = uuid("id")

    /** Property this booking belongs to. */
    val propertyId = uuid("property_id").references(PropertiesTable.id)

    /** Cognito `sub` of the tenant who created the booking. */
    val tenantId = text("tenant_id")

    /** Inclusive booking start date. */
    val startDate = date("start_date")

    /** Exclusive booking end date. */
    val endDate = date("end_date")

    /**
     * Lifecycle status of the booking.
     * Allowed values: `confirmed`, `cancelled`.
     */
    val status = text("status").default("confirmed")

    /** Row creation timestamp (managed by the repository or database). */
    val createdAt = timestampWithTimeZone("created_at")

    /** Row last-updated timestamp (managed by the repository or database). */
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
