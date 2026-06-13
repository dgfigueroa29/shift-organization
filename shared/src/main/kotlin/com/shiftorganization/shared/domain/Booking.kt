package com.shiftorganization.shared.domain

import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Persistent booking record stored in PostgreSQL.
 */
data class Booking(
    val id: String,
    val propertyId: String,
    val tenantId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)
