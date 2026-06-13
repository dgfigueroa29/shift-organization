package com.shiftorganization.lambda.properties

import com.shiftorganization.shared.auth.COGNITO_JWT_AUTH
import com.shiftorganization.shared.domain.Property
import com.shiftorganization.shared.domain.Role
import com.shiftorganization.shared.domain.UserPrincipal
import com.shiftorganization.shared.model.PagedResult
import com.shiftorganization.shared.model.PropertySearchFilter
import com.shiftorganization.shared.model.PropertySummary
import com.shiftorganization.shared.service.PropertySearchService
import com.shiftorganization.shared.service.PropertyService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.OffsetDateTime

class PropertiesModuleTest {

    private companion object {
        fun Application.configureApp(role: Role = Role.OWNER) {
            install(Authentication) {
                bearer(COGNITO_JWT_AUTH) {
                    authenticate { UserPrincipal("user-1", role) }
                }
            }
        }
    }

    private val sampleProperty = Property(
        id = "prop-1", ownerId = "user-1", address = "123 Main St",
        description = "Nice place", pricePerNight = 150.0,
        status = "available", createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )

    @Test
    fun `POST properties creates property and returns 201`() = testApplication {
        val propertyService = mock<PropertyService>()
        val propertySearchService = mock<PropertySearchService>()
        whenever(propertyService.create(any(), any())).thenReturn(sampleProperty)
        application {
            configureApp()
            propertiesModule(propertyService, propertySearchService)
        }
        val response = client.post("/properties") {
            header(HttpHeaders.Authorization, "Bearer token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TextContent("""{"address":"123 Main St","pricePerNight":150.0}""", ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `POST properties rejects TENANT role with 403`() = testApplication {
        val propertyService = mock<PropertyService>()
        val propertySearchService = mock<PropertySearchService>()
        application {
            configureApp(role = Role.TENANT)
            propertiesModule(propertyService, propertySearchService)
        }
        val response = client.post("/properties") {
            header(HttpHeaders.Authorization, "Bearer token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TextContent("""{"address":"123 Main St","pricePerNight":150.0}""", ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET properties by id returns 200 when property exists`() = testApplication {
        val propertyService = mock<PropertyService>()
        val propertySearchService = mock<PropertySearchService>()
        whenever(propertyService.findById("prop-1")).thenReturn(sampleProperty)
        application {
            configureApp()
            propertiesModule(propertyService, propertySearchService)
        }
        val response = client.get("/properties/prop-1") {
            header(HttpHeaders.Authorization, "Bearer token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET properties by id returns 404 when not found`() = testApplication {
        val propertyService = mock<PropertyService>()
        val propertySearchService = mock<PropertySearchService>()
        whenever(propertyService.findById("unknown")).thenReturn(null)
        application {
            configureApp()
            propertiesModule(propertyService, propertySearchService)
        }
        val response = client.get("/properties/unknown") {
            header(HttpHeaders.Authorization, "Bearer token")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PUT properties by id updates property and returns 200`() = testApplication {
        val propertyService = mock<PropertyService>()
        val propertySearchService = mock<PropertySearchService>()
        whenever(propertyService.update(any(), any(), any())).thenReturn(sampleProperty)
        application {
            configureApp()
            propertiesModule(propertyService, propertySearchService)
        }
        val response = client.put("/properties/prop-1") {
            header(HttpHeaders.Authorization, "Bearer token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TextContent("""{"address":"456 Oak St","pricePerNight":200.0}""", ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `DELETE properties by id returns 204`() = testApplication {
        val propertyService = mock<PropertyService>()
        val propertySearchService = mock<PropertySearchService>()
        application {
            configureApp()
            propertiesModule(propertyService, propertySearchService)
        }
        val response = client.delete("/properties/prop-1") {
            header(HttpHeaders.Authorization, "Bearer token")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        verify(propertyService).delete("prop-1", UserPrincipal("user-1", Role.OWNER))
    }

    @Test
    fun `GET properties search returns 200 with results`() = testApplication {
        val propertyService = mock<PropertyService>()
        val propertySearchService = mock<PropertySearchService>()
        val results = PagedResult(
            items = listOf(PropertySummary("prop-1", "user-1", "addr", null, 100.0, "status", "now")),
            total = 1, page = 0, size = 20
        )
        whenever(propertySearchService.search(any<PropertySearchFilter>())).thenReturn(results)
        application {
            configureApp()
            propertiesModule(propertyService, propertySearchService)
        }
        val response = client.get("/properties/search") {
            header(HttpHeaders.Authorization, "Bearer token")
            parameter("location", "Main")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
