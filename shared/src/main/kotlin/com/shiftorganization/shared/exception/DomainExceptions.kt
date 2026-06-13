package com.shiftorganization.shared.exception

// ---------------------------------------------------------------------------
// Domain exception hierarchy
//
// All exceptions extend DomainException so that the Ktor StatusPages plugin
// can catch the sealed hierarchy with a single handler and delegate to the
// specific subtype for the HTTP status mapping.
// ---------------------------------------------------------------------------

/**
 * Base class for all application-level domain exceptions.
 * Catch this type in the Ktor `StatusPages` plugin to handle every domain error.
 */
sealed class DomainException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

// ---------------------------------------------------------------------------
// 404 Not Found
// ---------------------------------------------------------------------------

/**
 * Thrown when a requested entity does not exist in the persistence layer.
 *
 * Maps to HTTP 404.
 *
 * @param entityId A string representation of the entity identifier (UUID or composite key).
 * @param entityType Optional human-readable type label, e.g. `"Property"` or `"Booking"`.
 */
class NotFoundException(
    val entityId: String,
    val entityType: String = "Entity"
) : DomainException("$entityType with id '$entityId' was not found")

// ---------------------------------------------------------------------------
// 403 Forbidden
// ---------------------------------------------------------------------------

/**
 * Thrown when the authenticated caller does not have sufficient ownership or
 * role privileges to perform the requested operation.
 *
 * Maps to HTTP 403.
 */
class ForbiddenException(
    message: String = "You do not have permission to perform this operation"
) : DomainException(message)

// ---------------------------------------------------------------------------
// 401 Unauthorized
// ---------------------------------------------------------------------------

/**
 * Thrown when the `Authorization` header is missing, the JWT has expired, or
 * the JWT signature / issuer validation fails.
 *
 * Maps to HTTP 401.
 */
class UnauthorizedException(
    message: String = "Authentication is required to access this resource"
) : DomainException(message)

// ---------------------------------------------------------------------------
// 409 Conflict
// ---------------------------------------------------------------------------

/**
 * Thrown when a booking request overlaps with one or more existing confirmed
 * bookings for the same property and date range.
 *
 * Maps to HTTP 409.
 *
 * @param conflictingBookingIds UUIDs of every confirmed booking that overlaps
 *                              with the requested date range.
 */
class ConflictException(
    val conflictingBookingIds: List<String>
) : DomainException(
    "Booking conflicts with existing reservations: ${conflictingBookingIds.joinToString()}"
)

// ---------------------------------------------------------------------------
// 422 Unprocessable Entity
// ---------------------------------------------------------------------------

/**
 * Thrown when a cancellation request is received less than 24 hours before
 * the booking's `startDate`.
 *
 * Maps to HTTP 422.
 *
 * @param bookingId UUID of the booking whose cancellation deadline has passed.
 */
class CancellationDeadlineException(
    val bookingId: String
) : DomainException(
    "Cancellation deadline has passed for booking '$bookingId'. " +
            "Cancellations must be made at least 24 hours before the start date."
)

// ---------------------------------------------------------------------------
// 503 Service Unavailable
// ---------------------------------------------------------------------------

/**
 * Thrown when the OpenSearch cluster is unreachable or returns an error that
 * prevents the search operation from completing.
 *
 * Maps to HTTP 503.
 */
class OpenSearchUnavailableException(
    message: String = "The OpenSearch cluster is currently unavailable",
    cause: Throwable? = null
) : DomainException(message, cause)

// ---------------------------------------------------------------------------
// 400 Bad Request
// ---------------------------------------------------------------------------

/**
 * Thrown when the request payload contains invalid data, malformed parameters,
 * or fails validation checks (e.g., invalid UUID, malformed date, negative price).
 *
 * Maps to HTTP 400.
 */
class BadRequestException(
    message: String
) : DomainException(message)

// ---------------------------------------------------------------------------
// 500 Internal Server Error
// ---------------------------------------------------------------------------

/**
 * Thrown for unrecoverable internal errors, e.g. when an EventBridge rule
 * registration fails after the DynamoDB record has already been written.
 *
 * Maps to HTTP 500.
 */
class InternalServerException(
    message: String,
    cause: Throwable? = null
) : DomainException(message, cause)
