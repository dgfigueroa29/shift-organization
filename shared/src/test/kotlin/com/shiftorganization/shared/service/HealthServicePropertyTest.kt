package com.shiftorganization.shared.service

import net.jqwik.api.*
import net.jqwik.api.constraints.AlphaChars
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.StringLength
import org.junit.jupiter.api.Assertions.*

/**
 * Property test for health check dependency identification.
 *
 * Validates:
 * - When all probes succeed: overall status is "healthy" and every dependency is "healthy".
 * - When any probe fails: overall status is "unhealthy", the failed dependency is identified,
 *   healthy dependencies remain "healthy".
 * - Error message from the failing probe is captured in the dependency status.
 * - All probes are always evaluated regardless of earlier failures.
 *
 * Validates: Requirements 9.4, 9.5
 *
 * Feature: shift-organization-mvp, Property: Health check dependency identification
 */
@Label("Feature: shift-organization-mvp, Property: Health check dependency identification")
class HealthServicePropertyTest {

    private val dependencyNames = listOf("rds", "dynamodb", "opensearch")

    // ---------------------------------------------------------------------------
    // Property: all healthy → overall healthy
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `when all probes succeed overall status is healthy`(
        @ForAll @IntRange(min = 1, max = 5) count: Int
    ) {
        val probes = (1..count).associate { "dep-$it" to HealthProbe { /* no-op */ } }
        val service = HealthService(probes)

        val response = service.check()

        assertEquals("healthy", response.status)
        probes.keys.forEach { name ->
            assertEquals("healthy", response.dependencies[name]?.status,
                "Dependency $name should be healthy")
            assertNull(response.dependencies[name]?.message,
                "Healthy dependency $name should have no error message")
        }
    }

    // ---------------------------------------------------------------------------
    // Property: any failing probe → overall unhealthy, failed dep identified
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `when any probe fails overall status is unhealthy and that dependency is identified`(
        @ForAll @IntRange(min = 0, max = 2) failingIndex: Int
    ) {
        val failingName = dependencyNames[failingIndex]
        val errorMessage = "connection refused at $failingName"

        val probes = dependencyNames.associateWith { name ->
            if (name == failingName) {
                HealthProbe { throw IllegalStateException(errorMessage) }
            } else {
                HealthProbe { /* healthy */ }
            }
        }
        val service = HealthService(probes)

        val response = service.check()

        assertEquals("unhealthy", response.status,
            "Overall status must be unhealthy when any dependency fails")

        val failedDep = response.dependencies[failingName]
        assertNotNull(failedDep, "Failed dependency must appear in the response")
        assertEquals("unhealthy", failedDep!!.status)
        assertTrue(
            failedDep.message?.contains(errorMessage) == true,
            "Error message must contain the probe exception message, got: ${failedDep.message}"
        )
    }

    // ---------------------------------------------------------------------------
    // Property: healthy deps remain healthy when others fail
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `healthy dependencies remain healthy when another dependency fails`(
        @ForAll @IntRange(min = 0, max = 2) failingIndex: Int
    ) {
        val failingName = dependencyNames[failingIndex]
        val probes = dependencyNames.associateWith { name ->
            if (name == failingName) {
                HealthProbe { throw RuntimeException("down") }
            } else {
                HealthProbe { /* healthy */ }
            }
        }
        val service = HealthService(probes)

        val response = service.check()

        dependencyNames.filter { it != failingName }.forEach { name ->
            assertEquals("healthy", response.dependencies[name]?.status,
                "Dependency $name should still be healthy even though $failingName failed")
        }
    }

    // ---------------------------------------------------------------------------
    // Property: all probes are evaluated regardless of failures
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `all probes are always evaluated even when earlier ones fail`(
        @ForAll @IntRange(min = 2, max = 5) count: Int
    ) {
        val evaluated = mutableSetOf<String>()

        val probes = (1..count).associate { i ->
            val name = "dep-$i"
            val probe = HealthProbe {
                evaluated.add(name)
                if (i == 1) throw RuntimeException("dep-1 is down")
            }
            name to probe
        }
        val service = HealthService(probes)

        service.check()

        assertEquals(
            probes.keys, evaluated,
            "Every probe must be evaluated even when the first one throws"
        )
    }

    // ---------------------------------------------------------------------------
    // Property: empty probes map → healthy with empty dependencies
    // ---------------------------------------------------------------------------

    @Property(tries = 10)
    fun `service with no probes reports healthy with empty dependencies map`(
        @ForAll @IntRange(min = 0, max = 0) ignored: Int
    ) {
        val service = HealthService(emptyMap())

        val response = service.check()

        assertEquals("healthy", response.status)
        assertTrue(response.dependencies.isEmpty())
    }

    // ---------------------------------------------------------------------------
    // Property: multiple failing deps → all identified as unhealthy
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `multiple failing dependencies are all identified as unhealthy`(
        @ForAll @IntRange(min = 2, max = 3) failCount: Int
    ) {
        val failingNames = dependencyNames.take(failCount)
        val probes = dependencyNames.associateWith { name ->
            if (name in failingNames) {
                HealthProbe { throw RuntimeException("$name unavailable") }
            } else {
                HealthProbe { /* healthy */ }
            }
        }
        val service = HealthService(probes)

        val response = service.check()

        assertEquals("unhealthy", response.status)
        failingNames.forEach { name ->
            assertEquals("unhealthy", response.dependencies[name]?.status,
                "Failing dependency $name should be reported as unhealthy")
            assertNotNull(response.dependencies[name]?.message,
                "Failing dependency $name should have an error message")
        }
    }
}
