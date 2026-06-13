package com.shiftorganization.shared.service

import kotlinx.serialization.Serializable

/**
 * A single dependency probe invoked by [HealthService.check].
 *
 * The lambda should perform the minimal operation that confirms the dependency
 * is reachable (e.g. `SELECT 1` for RDS, `listTables` for DynamoDB, a cluster
 * info request for OpenSearch). Throwing any [Throwable] signals that the
 * dependency is unavailable.
 */
fun interface HealthProbe {
    fun probe()
}

// ---------------------------------------------------------------------------
// Response models
// ---------------------------------------------------------------------------

/** Status detail for a single dependency. */
@Serializable
data class DependencyStatus(
    /** `"healthy"` or `"unhealthy"` */
    val status: String,
    /** Non-null when [status] is `"unhealthy"`, contains the error message. */
    val message: String? = null
)

/** Top-level health check response returned by `GET /health`. */
@Serializable
data class HealthResponse(
    /** `"healthy"` if all dependencies are up, `"unhealthy"` otherwise. */
    val status: String,
    /** Per-dependency breakdown keyed by dependency name. */
    val dependencies: Map<String, DependencyStatus>
)

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

/**
 * Runs each registered [HealthProbe] and aggregates the results into a
 * [HealthResponse].
 *
 * The overall [HealthResponse.status] is `"healthy"` only when every probe
 * succeeds. A single probe failure marks the overall status `"unhealthy"` and
 * records the error message in the corresponding [DependencyStatus] entry.
 *
 * Requirement 9.4: `GET /health` returns HTTP 200 with dependency statuses.
 * Requirement 9.5: Any unavailable dependency causes HTTP 503.
 *
 * @param probes Map of dependency names (e.g. `"rds"`, `"dynamodb"`) to
 *               their corresponding [HealthProbe] implementations.
 */
class HealthService(
    private val probes: Map<String, HealthProbe>
) {
    /**
     * Runs all probes and returns an aggregated [HealthResponse].
     * All probes are always attempted — a failure in one does not skip others.
     */
    fun check(): HealthResponse {
        val dependencyStatuses = probes.mapValues { (_, probe) ->
            runCatching { probe.probe() }.fold(
                onSuccess = { DependencyStatus(status = "healthy") },
                onFailure = { e ->
                    DependencyStatus(
                        status = "unhealthy",
                        message = e.message ?: e.javaClass.simpleName
                    )
                }
            )
        }

        val overallStatus = if (dependencyStatuses.values.all { it.status == "healthy" }) {
            "healthy"
        } else {
            "unhealthy"
        }

        return HealthResponse(
            status = overallStatus,
            dependencies = dependencyStatuses
        )
    }
}
