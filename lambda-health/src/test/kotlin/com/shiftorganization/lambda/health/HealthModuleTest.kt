package com.shiftorganization.lambda.health

import com.shiftorganization.shared.service.HealthProbe
import com.shiftorganization.shared.service.HealthService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthModuleTest {

    @Test
    fun `GET health returns 200 when all dependencies are healthy`() = testApplication {
        application {
            healthModule(
                HealthService(
                    mapOf("rds" to HealthProbe {}, "dynamodb" to HealthProbe {})
                )
            )
        }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("healthy", json["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET health returns 503 when a dependency is unhealthy`() = testApplication {
        application {
            healthModule(
                HealthService(
                    mapOf(
                        "rds" to HealthProbe {},
                        "dynamodb" to HealthProbe { throw RuntimeException("timeout") }
                    )
                )
            )
        }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("unhealthy", json["status"]?.jsonPrimitive?.content)
    }
}
