package com.shiftorganization.shared.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Property request / response models
// ---------------------------------------------------------------------------

/**
 * Request body for `POST /properties` and `PUT /properties/{id}`.
 */
@Serializable
data class CreatePropertyRequest(
    val address: String,
    val description: String? = null,
    val pricePerNight: Double
)

/**
 * Response body returned for property create, update, and read operations.
 */
@Serializable
data class PropertyResponse(
    val id: String,
    val ownerId: String,
    val address: String,
    val description: String?,
    val pricePerNight: Double,
    /** One of: `available`, `unavailable`, `deleted` */
    val status: String,
    val createdAt: String,
    val updatedAt: String
)

/**
 * Condensed view of a property as returned inside OpenSearch search results.
 */
@Serializable
data class PropertySummary(
    val id: String,
    val ownerId: String,
    val address: String,
    val description: String?,
    val pricePerNight: Double,
    val status: String,
    val updatedAt: String
)

// ---------------------------------------------------------------------------
// Property search filter
// ---------------------------------------------------------------------------

/**
 * Query parameters accepted by `GET /properties/search`.
 *
 * @param location  Full-text term matched against the property `address` field.
 * @param priceMin  Inclusive lower bound on `pricePerNight`.
 * @param priceMax  Inclusive upper bound on `pricePerNight`.
 * @param available When `true`, only `available` properties are returned.
 * @param page      Zero-based page index (default 0).
 * @param size      Page size; capped at 20 by the search service.
 */
@Serializable
data class PropertySearchFilter(
    val location: String? = null,
    val priceMin: Double? = null,
    val priceMax: Double? = null,
    val available: Boolean? = null,
    val page: Int = 0,
    val size: Int = 20
)
