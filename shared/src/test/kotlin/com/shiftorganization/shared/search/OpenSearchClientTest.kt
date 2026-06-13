package com.shiftorganization.shared.search

import com.shiftorganization.shared.domain.Property
import com.shiftorganization.shared.exception.OpenSearchUnavailableException
import com.shiftorganization.shared.model.PropertySearchFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.opensearch.client.opensearch._types.query_dsl.Query
import java.io.IOException
import java.time.OffsetDateTime

class OpenSearchClientTest {

    private class FakeBackend : OpenSearchClient.Backend {
        var indexedDocument: Map<String, Any?>? = null
        var deletedId: String? = null
        var capturedQuery: Query? = null
        var capturedFrom: Int? = null
        var capturedSize: Int? = null
        var searchPage: OpenSearchClient.SearchPage = OpenSearchClient.SearchPage(emptyList(), 0)
        var indexFailure: Throwable? = null
        var searchFailure: Throwable? = null

        override fun indexProperty(document: Map<String, Any?>) {
            indexFailure?.let { throw it }
            indexedDocument = document
        }

        override fun deleteProperty(id: String) {
            deletedId = id
        }

        override fun search(query: Query, from: Int, size: Int): OpenSearchClient.SearchPage {
            searchFailure?.let { throw it }
            capturedQuery = query
            capturedFrom = from
            capturedSize = size
            return searchPage
        }
    }

    @Test
    fun `indexProperty maps property fields to the properties index document`() {
        val backend = FakeBackend()
        val client = OpenSearchClient(backend)
        val property = Property(
            id = "prop-1",
            ownerId = "owner-1",
            address = "123 Main St",
            description = "Bright flat",
            pricePerNight = 120.5,
            status = "available",
            createdAt = OffsetDateTime.parse("2026-01-01T10:15:30Z"),
            updatedAt = OffsetDateTime.parse("2026-01-02T10:15:30Z")
        )

        client.indexProperty(property)

        val document = backend.indexedDocument
        assertNotNull(document)
        assertEquals("prop-1", document!!["id"])
        assertEquals("owner-1", document["ownerId"])
        assertEquals("123 Main St", document["address"])
        assertEquals("Bright flat", document["description"])
        assertEquals(120.5, (document["priceNight"] as Number).toDouble())
        assertEquals("available", document["status"])
        assertEquals("2026-01-02T10:15:30Z", document["updatedAt"])
    }

    @Test
    fun `deleteProperty delegates the document id to the backend`() {
        val backend = FakeBackend()
        val client = OpenSearchClient(backend)

        client.deleteProperty("prop-99")

        assertEquals("prop-99", backend.deletedId)
    }

    @Test
    fun `search builds the expected query and caps the page size at 20`() {
        val backend = FakeBackend().apply {
            searchPage = OpenSearchClient.SearchPage(
                hits = listOf(
                    mapOf(
                        "id" to "prop-1",
                        "ownerId" to "owner-1",
                        "address" to "123 Main St",
                        "description" to "Bright flat",
                        "priceNight" to 120.5,
                        "status" to "available",
                        "updatedAt" to "2026-01-02T10:15:30Z"
                    )
                ),
                total = 1
            )
        }
        val client = OpenSearchClient(backend)

        val result = client.search(
            PropertySearchFilter(
                location = "Main",
                priceMin = 100.0,
                priceMax = 150.0,
                available = true,
                page = 2,
                size = 80
            )
        )

        assertEquals(1, result.items.size)
        assertEquals(1, result.total)
        assertEquals(2, result.page)
        assertEquals(20, result.size)
        assertEquals(40, backend.capturedFrom)
        assertEquals(20, backend.capturedSize)

        val bool = backend.capturedQuery!!.bool()

        assertEquals(1, bool.must().size)
        val locationMatch = bool.must()[0]
        assertEquals(Query.Kind.Match, locationMatch._kind())
        assertEquals("address", locationMatch.match().field())

        assertEquals(3, bool.filter().size)
        val byKind = bool.filter().groupBy { it._kind() }

        val ranges = byKind[Query.Kind.Range].orEmpty()
        assertEquals(2, ranges.size)
        ranges.forEach { assertEquals("priceNight", it.range().field()) }

        val terms = byKind[Query.Kind.Term].orEmpty()
        assertEquals(1, terms.size)
        val statusTerm = terms[0].term()
        assertEquals("status", statusTerm.field())
        assertEquals("available", statusTerm.value().stringValue())
    }

    @Test
    fun `search wraps backend IO failures as OpenSearchUnavailableException`() {
        val backend = FakeBackend().apply {
            searchFailure = IOException("cluster unreachable")
        }
        val client = OpenSearchClient(backend)

        val ex = org.junit.jupiter.api.Assertions.assertThrows(OpenSearchUnavailableException::class.java) {
            client.search(PropertySearchFilter(location = "Main"))
        }

        assertNotNull(ex.cause)
        assertEquals("cluster unreachable", ex.cause!!.message)
    }
}
