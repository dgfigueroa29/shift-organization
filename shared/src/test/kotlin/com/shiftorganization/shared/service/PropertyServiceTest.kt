package com.shiftorganization.shared.service

import com.shiftorganization.shared.db.PropertyRepository
import com.shiftorganization.shared.domain.Property
import com.shiftorganization.shared.domain.Role
import com.shiftorganization.shared.domain.UserPrincipal
import com.shiftorganization.shared.exception.ForbiddenException
import com.shiftorganization.shared.exception.NotFoundException
import com.shiftorganization.shared.model.CreatePropertyRequest
import com.shiftorganization.shared.search.OpenSearchClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*
import org.opensearch.client.opensearch._types.query_dsl.Query
import java.time.OffsetDateTime

class PropertyServiceTest {

    private class FakeBackend : OpenSearchClient.Backend {
        var indexedDocument: Map<String, Any?>? = null
        var deletedId: String? = null
        var indexFailure: Throwable? = null

        override fun indexProperty(document: Map<String, Any?>) {
            indexFailure?.let { throw it }
            indexedDocument = document
        }

        override fun deleteProperty(id: String) {
            deletedId = id
        }

        override fun search(query: Query, from: Int, size: Int): OpenSearchClient.SearchPage =
            OpenSearchClient.SearchPage(emptyList(), 0)
    }

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-06-04T12:00:00Z")

    private fun property(
        id: String = "prop-1",
        ownerId: String = "owner-1",
        status: String = "available"
    ): Property = Property(
        id = id,
        ownerId = ownerId,
        address = "123 Main St",
        description = "Flat",
        pricePerNight = 100.0,
        status = status,
        createdAt = now,
        updatedAt = now
    )

    private val command = CreatePropertyRequest(
        address = "123 Main St",
        description = "Flat",
        pricePerNight = 100.0
    )

    private fun newService(
        repo: PropertyRepository,
        backend: FakeBackend
    ): Pair<PropertyService, OpenSearchClient> {
        val searchClient = OpenSearchClient(backend)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        return PropertyService(repo, searchClient, scope, Dispatchers.Unconfined) to searchClient
    }

    @Test
    fun `create persists the property and syncs it to OpenSearch`() {
        val repo = mock<PropertyRepository>()
        val backend = FakeBackend()
        val (service, _) = newService(repo, backend)
        val saved = property()
        whenever(repo.insert(command, "owner-1")).thenReturn(saved)

        val result = service.create(command, UserPrincipal("owner-1", Role.OWNER))

        assertSame(saved, result)
        assertEquals("prop-1", backend.indexedDocument?.get("id"))
    }

    @Test
    fun `create swallows OpenSearch failures so the HTTP response is unaffected`() {
        val repo = mock<PropertyRepository>()
        val backend = FakeBackend().apply { indexFailure = RuntimeException("cluster down") }
        val (service, _) = newService(repo, backend)
        val saved = property()
        whenever(repo.insert(command, "owner-1")).thenReturn(saved)

        val result = service.create(command, UserPrincipal("owner-1", Role.OWNER))

        assertSame(saved, result)
    }

    @Test
    fun `update rejects a different owner with ForbiddenException`() {
        val repo = mock<PropertyRepository>()
        val (service, _) = newService(repo, FakeBackend())
        whenever(repo.findById("prop-1")).thenReturn(property(ownerId = "owner-A"))

        assertThrows<ForbiddenException> {
            service.update("prop-1", command, UserPrincipal("owner-B", Role.OWNER))
        }
        verify(repo, never()).update(any<String>(), any<CreatePropertyRequest>())
    }

    @Test
    fun `update rejects a tenant with ForbiddenException`() {
        val repo = mock<PropertyRepository>()
        val (service, _) = newService(repo, FakeBackend())
        whenever(repo.findById("prop-1")).thenReturn(property(ownerId = "owner-A"))

        assertThrows<ForbiddenException> {
            service.update("prop-1", command, UserPrincipal("tenant-1", Role.TENANT))
        }
    }

    @Test
    fun `update allows an admin even when the ownerId differs`() {
        val repo = mock<PropertyRepository>()
        val backend = FakeBackend()
        val (service, _) = newService(repo, backend)
        val existing = property(ownerId = "owner-A")
        val updated = existing.copy(address = "456 New St")
        whenever(repo.findById("prop-1")).thenReturn(existing)
        whenever(repo.update("prop-1", command)).thenReturn(updated)

        val result = service.update("prop-1", command, UserPrincipal("admin-1", Role.ADMIN))

        assertSame(updated, result)
        assertEquals("prop-1", backend.indexedDocument?.get("id"))
    }

    @Test
    fun `update throws NotFoundException when the property does not exist`() {
        val repo = mock<PropertyRepository>()
        val (service, _) = newService(repo, FakeBackend())
        whenever(repo.findById("missing")).thenReturn(null)

        assertThrows<NotFoundException> {
            service.update("missing", command, UserPrincipal("owner-1", Role.OWNER))
        }
    }

    @Test
    fun `delete soft-deletes and removes the document from OpenSearch`() {
        val repo = mock<PropertyRepository>()
        val backend = FakeBackend()
        val (service, _) = newService(repo, backend)
        whenever(repo.findById("prop-1")).thenReturn(property())

        service.delete("prop-1", UserPrincipal("owner-1", Role.OWNER))

        verify(repo).softDelete("prop-1")
        assertEquals("prop-1", backend.deletedId)
    }

    @Test
    fun `delete rejects a different owner without touching the repository`() {
        val repo = mock<PropertyRepository>()
        val (service, _) = newService(repo, FakeBackend())
        whenever(repo.findById("prop-1")).thenReturn(property(ownerId = "owner-A"))

        assertThrows<ForbiddenException> {
            service.delete("prop-1", UserPrincipal("owner-B", Role.OWNER))
        }
        verify(repo, never()).softDelete(any())
    }
}
