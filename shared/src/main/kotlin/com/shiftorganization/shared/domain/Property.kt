package com.shiftorganization.shared.domain

import java.time.OffsetDateTime

/**
 * Persistent property record stored in PostgreSQL.
 */
data class Property(
    val id: String,
    val ownerId: String,
    val address: String,
    val description: String?,
    val pricePerNight: Double,
    val status: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)
