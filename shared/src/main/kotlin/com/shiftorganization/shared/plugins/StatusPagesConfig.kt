package com.shiftorganization.shared.plugins

import com.shiftorganization.shared.exception.*
import io.ktor.http.HttpStatusCode
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("StatusPages")

// ---------------------------------------------------------------------------
// Error response body
// ---------------------------------------------------------------------------

/**
 * Consistent JSON error body returned for every domain and unexpected exception.
 *
 * Standard shape: `{"error":..., "message":..., "correlationId":...}`
 *
 * For [ConflictException] an optional [conflictingBookingIds] field is appended when
 * the list is non-empty.
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    @SerialName("correlationId")
    val correlationId: String,
    val conflictingBookingIds: List<String>? = null
)

// ---------------------------------------------------------------------------
// StatusPages extension
// ---------------------------------------------------------------------------

/**
 * Configures Ktor's [StatusPages] plugin to map every domain exception defined in
 * [com.shiftorganization.shared.exceptions] to its corresponding HTTP status code and a
 * consistent [ErrorResponse] JSON body.
 *
 * Install order: this should be installed **after** [CorrelationIdPlugin] so that the
 * correlation ID is already available on the call.
 *
 * | Exception                       | HTTP status |
 * |---------------------------------|-------------|
 * | NotFoundException               | 404         |
 * | ForbiddenException              | 403         |
 * | UnauthorizedException           | 401         |
 * | ConflictException               | 409         |
 * | CancellationDeadlineException   | 422         |
 * | OpenSearchUnavailableException  | 503         |
 * | InternalServerException         | 500         |
 * | Generic Throwable               | 500         |
 *
 * Requirements: 1.1, 1.2, 1.3, 9.6
 */
fun Application.configureStatusPages() {
    install(StatusPages) {

        // 404 – entity not found
        exception<NotFoundException> { call, ex ->
            val correlationId = call.safeCorrelationId()
            logger.warn("Not found: {} [correlationId={}]", ex.message, correlationId)
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(
                    error = "NOT_FOUND",
                    message = ex.message ?: "Resource not found",
                    correlationId = correlationId
                )
            )
        }

        // 403 – forbidden (insufficient role or ownership)
        exception<ForbiddenException> { call, ex ->
            val correlationId = call.safeCorrelationId()
            logger.warn("Forbidden: {} [correlationId={}]", ex.message, correlationId)
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse(
                    error = "FORBIDDEN",
                    message = ex.message ?: "Access to this resource is forbidden",
                    correlationId = correlationId
                )
            )
        }

        // 401 – unauthorized (missing or invalid JWT)
        exception<UnauthorizedException> { call, ex ->
            val correlationId = call.safeCorrelationId()
            logger.warn("Unauthorized: {} [correlationId={}]", ex.message, correlationId)
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse(
                    error = "UNAUTHORIZED",
                    message = ex.message ?: "Authentication required",
                    correlationId = correlationId
                )
            )
        }

        // 409 – booking date conflict; include all conflicting IDs when present
        exception<ConflictException> { call, ex ->
            val correlationId = call.safeCorrelationId()
            logger.warn(
                "Conflict: {} ids={} [correlationId={}]",
                ex.message,
                ex.conflictingBookingIds,
                correlationId
            )
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse(
                    error = "CONFLICT",
                    message = ex.message ?: "Booking conflicts with existing reservations",
                    correlationId = correlationId,
                    conflictingBookingIds = ex.conflictingBookingIds.takeIf { it.isNotEmpty() }
                )
            )
        }

        // 422 – cancellation deadline passed
        exception<CancellationDeadlineException> { call, ex ->
            val correlationId = call.safeCorrelationId()
            logger.warn("Cancellation deadline: {} [correlationId={}]", ex.message, correlationId)
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse(
                    error = "CANCELLATION_DEADLINE_PASSED",
                    message = ex.message ?: "Cancellation deadline has passed",
                    correlationId = correlationId
                )
            )
        }

        // 503 – OpenSearch unavailable
        exception<OpenSearchUnavailableException> { call, ex ->
            val correlationId = call.safeCorrelationId()
            logger.error("OpenSearch unavailable: {} [correlationId={}]", ex.message, correlationId)
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    error = "SERVICE_UNAVAILABLE",
                    message = ex.message ?: "The search service is temporarily unavailable",
                    correlationId = correlationId
                )
            )
        }

        // 500 – known internal error (e.g. EventBridge registration failure)
        exception<InternalServerException> { call, ex ->
            val correlationId = call.safeCorrelationId()
            logger.error("Internal server error: {} [correlationId={}]", ex.message, correlationId, ex.cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    error = "INTERNAL_SERVER_ERROR",
                    message = ex.message ?: "An internal server error occurred",
                    correlationId = correlationId
                )
            )
        }

         // 400 – bad request (validation errors)
        exception<BadRequestException> { call, ex ->
            val correlationId = call.safeCorrelationId()
            logger.warn("Bad request: {} [correlationId={}]", ex.message, correlationId)
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "BAD_REQUEST",
                    message = ex.message ?: "Invalid request parameters",
                    correlationId = correlationId
                )
            )
        }

        // 500 – catch-all for any unexpected exception
        exception<Throwable> { call, ex ->
            val correlationId = call.safeCorrelationId()
            logger.error("Unexpected error [correlationId={}]", correlationId, ex)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    error = "INTERNAL_SERVER_ERROR",
                    message = "An unexpected error occurred",
                    correlationId = correlationId
                )
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Returns the correlation ID from the call attributes if [CorrelationIdPlugin] has run,
 * or a newly generated UUID as a fallback (e.g., when StatusPages fires before the plugin).
 */
private fun ApplicationCall.safeCorrelationId(): String =
    attributes.getOrNull(CorrelationIdKey) ?: java.util.UUID.randomUUID().toString()
