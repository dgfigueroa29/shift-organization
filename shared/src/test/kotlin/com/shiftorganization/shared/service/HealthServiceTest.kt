package com.shiftorganization.shared.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealthServiceTest {

    @Test
    fun `check returns healthy when all dependency probes succeed`() {
        val service = HealthService(
            mapOf(
                "rds" to HealthProbe { },
                "dynamodb" to HealthProbe { },
                "opensearch" to HealthProbe { }
            )
        )

        val response = service.check()

        assertEquals("healthy", response.status)
        assertEquals("healthy", response.dependencies["rds"]?.status)
        assertEquals("healthy", response.dependencies["dynamodb"]?.status)
        assertEquals("healthy", response.dependencies["opensearch"]?.status)
    }

    @Test
    fun `check returns unhealthy and identifies the failed dependency`() {
        val service = HealthService(
            mapOf(
                "rds" to HealthProbe { },
                "dynamodb" to HealthProbe { throw IllegalStateException("table unavailable") },
                "opensearch" to HealthProbe { }
            )
        )

        val response = service.check()

        assertEquals("unhealthy", response.status)
        assertEquals("healthy", response.dependencies["rds"]?.status)
        assertEquals("unhealthy", response.dependencies["dynamodb"]?.status)
        assertTrue(response.dependencies["dynamodb"]?.message!!.contains("table unavailable"))
        assertEquals("healthy", response.dependencies["opensearch"]?.status)
    }
}
