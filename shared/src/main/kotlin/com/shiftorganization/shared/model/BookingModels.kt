package com.shiftorganization.shared.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Booking request / response models
// ---------------------------------------------------------------------------

/**
 * Request body for `POST /bookings`.
 *
 * @param propertyId UUID of the property to book.
 * @param startDate  ISO-8601 date string (inclusive), e.g. `"2024-06-01"`.
 * @param endDate    ISO-8601 date string (exclusive), e.g. `"2024-06-07"`.
 */
@Serializable
data class CreateBookingRequest(
    val propertyId: String,
    val startDate: String,
    val endDate: String
)

/**
 * Response body returned for booking create and read operations.
 */
@Serializable
data class BookingResponse(
    val id: String,
    val propertyId: String,
    val tenantId: String,
    val startDate: String,
    val endDate: String,
    /** One of: `confirmed`, `cancelled` */
    val status: String,
    val createdAt: String
)
