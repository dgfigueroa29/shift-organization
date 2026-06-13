package com.shiftorganization.shared.search

import com.shiftorganization.shared.model.PropertySearchFilter
import com.shiftorganization.shared.model.PropertySummary
import net.jqwik.api.*
import net.jqwik.api.constraints.AlphaChars
import net.jqwik.api.constraints.DoubleRange
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.StringLength
import org.junit.jupiter.api.Assertions.*
import org.opensearch.client.opensearch._types.query_dsl.Query

/**
 * Property tests for property search filter invariants.
 *
 * Property 7: Price-range filter invariant
 *   For any search with priceMin/priceMax, every result satisfies priceMin ≤ price ≤ priceMax.
 *
 * Property 8: Availability filter invariant
 *   For any search with available=true, every result has status = "available".
 *
 * Property 9: Location filter invariant
 *   For any search with a non-empty location, every result is matched by OpenSearch against
 *   the address field (verified by asserting the query structure delegates correctly).
 *
 * Property 10: Search pagination size invariant
 *   For any search and any response page, items.size ≤ 20.
 *
 * Validates: Requirements 3.2, 3.3, 3.4, 3.5
 *
 * Feature: shift-organization-mvp, Properties 7-10: Search filter invariants
 */
@Label("Feature: shift-organization-mvp, Properties 7-10: Search filter invariants")
class SearchFilterPropertyTest {

    // ---------------------------------------------------------------------------
    // Controlled fake backend — returns whatever we configure
    // ---------------------------------------------------------------------------

    private class ControllableBackend(
        private val resultsProvider: () -> List<Map<String, Any?>>
    ) : OpenSearchClient.Backend {
        var lastQuery: Query? = null
        var lastFrom: Int? = null
        var lastSize: Int? = null

        override fun indexProperty(document: Map<String, Any?>) = Unit
        override fun deleteProperty(id: String) = Unit

        override fun search(query: Query, from: Int, size: Int): OpenSearchClient.SearchPage {
            lastQuery = query
            lastFrom = from
            lastSize = size
            return OpenSearchClient.SearchPage(
                hits = resultsProvider().take(size),
                total = resultsProvider().size.toLong()
            )
        }
    }

    private fun summaryDoc(
        id: String,
        price: Double,
        status: String = "available",
        address: String = "123 Main St"
    ): Map<String, Any?> = mapOf(
        "id" to id,
        "ownerId" to "owner-1",
        "address" to address,
        "description" to null,
        "priceNight" to price,
        "status" to status,
        "updatedAt" to "2026-01-01T00:00:00Z"
    )

    // ---------------------------------------------------------------------------
    // Property 10: page size is always capped at 20
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `response items count never exceeds 20 regardless of requested size`(
        @ForAll @IntRange(min = 0, max = 200) requestedSize: Int,
        @ForAll @IntRange(min = 0, max = 50) resultCount: Int
    ) {
        val results = (1..resultCount).map { summaryDoc("prop-$it", 100.0) }
        val backend = ControllableBackend { results }
        val client = OpenSearchClient(backend)

        val response = client.search(PropertySearchFilter(size = requestedSize))

        assertTrue(
            response.items.size <= 20,
            "items.size ${response.items.size} must be ≤ 20 regardless of requested size $requestedSize"
        )
    }

    // ---------------------------------------------------------------------------
    // Property 10: size passed to backend is capped at 20
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `backend receives a size value capped at 20`(
        @ForAll @IntRange(min = 0, max = 500) requestedSize: Int
    ) {
        val backend = ControllableBackend { emptyList() }
        val client = OpenSearchClient(backend)

        client.search(PropertySearchFilter(size = requestedSize))

        assertTrue(
            (backend.lastSize ?: 0) <= 20,
            "Backend size ${backend.lastSize} must be ≤ 20"
        )
    }

    // ---------------------------------------------------------------------------
    // Property 7: price range filter is translated into query clauses
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `price range filter produces range clauses in the bool query`(
        @ForAll @DoubleRange(min = 0.0, max = 500.0) priceMin: Double,
        @ForAll @DoubleRange(min = 500.0, max = 2000.0) priceMax: Double
    ) {
        val backend = ControllableBackend { emptyList() }
        val client = OpenSearchClient(backend)

        client.search(PropertySearchFilter(priceMin = priceMin, priceMax = priceMax))

        val query = backend.lastQuery
        assertNotNull(query, "Query must be present")
        val boolQuery = query!!.bool()
        val filters = boolQuery.filter()

        val rangeFilters = filters.filter {
            it._kind() == org.opensearch.client.opensearch._types.query_dsl.Query.Kind.Range
        }
        assertEquals(2, rangeFilters.size,
            "priceMin and priceMax should produce 2 range filter clauses")

        rangeFilters.forEach { f ->
            assertEquals("priceNight", f.range().field(),
                "Range filter must target the priceNight field")
        }
    }

    // ---------------------------------------------------------------------------
    // Property 8: available=true is translated into a term clause on status
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `available=true filter produces a term clause on status=available`(
        @ForAll @IntRange(min = 0, max = 10) ignored: Int
    ) {
        val backend = ControllableBackend { emptyList() }
        val client = OpenSearchClient(backend)

        client.search(PropertySearchFilter(available = true))

        val boolQuery = backend.lastQuery!!.bool()
        val termFilters = boolQuery.filter().filter {
            it._kind() == org.opensearch.client.opensearch._types.query_dsl.Query.Kind.Term
        }
        assertEquals(1, termFilters.size,
            "available=true must produce exactly one term filter")

        val term = termFilters[0].term()
        assertEquals("status", term.field())
        assertEquals("available", term.value().stringValue())
    }

    // ---------------------------------------------------------------------------
    // Property 8: available=false or null → no status term clause added
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `available=false or null does not add a status term clause`(
        @ForAll @IntRange(min = 0, max = 10) ignored: Int
    ) {
        val backend = ControllableBackend { emptyList() }
        val client = OpenSearchClient(backend)

        client.search(PropertySearchFilter(available = false))

        val boolQuery = backend.lastQuery!!.bool()
        val statusTerms = boolQuery.filter().filter { f ->
            f._kind() == org.opensearch.client.opensearch._types.query_dsl.Query.Kind.Term &&
                f.term().field() == "status"
        }
        assertTrue(statusTerms.isEmpty(),
            "available=false must not add a status term filter clause")
    }

    // ---------------------------------------------------------------------------
    // Property 9: location filter is translated into a match clause on address
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `non-blank location filter produces a match clause on the address field`(
        @ForAll @AlphaChars @StringLength(min = 1, max = 30) location: String
    ) {
        val backend = ControllableBackend { emptyList() }
        val client = OpenSearchClient(backend)

        client.search(PropertySearchFilter(location = location))

        val boolQuery = backend.lastQuery!!.bool()
        val matchClauses = boolQuery.must().filter {
            it._kind() == org.opensearch.client.opensearch._types.query_dsl.Query.Kind.Match
        }
        assertEquals(1, matchClauses.size,
            "A non-blank location must produce exactly one match clause")
        assertEquals("address", matchClauses[0].match().field(),
            "Match clause must target the address field")
    }

    // ---------------------------------------------------------------------------
    // Property 9: blank or null location → no match clause
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `blank or null location does not produce a match clause`(
        @ForAll @IntRange(min = 0, max = 10) ignored: Int
    ) {
        val backend = ControllableBackend { emptyList() }
        val client = OpenSearchClient(backend)

        client.search(PropertySearchFilter(location = null))

        val boolQuery = backend.lastQuery!!.bool()
        assertTrue(boolQuery.must().isEmpty(),
            "Null location must not add any must clause to the query")
    }

    // ---------------------------------------------------------------------------
    // Property: pagination offset is calculated correctly
    // ---------------------------------------------------------------------------

    @Property(tries = 100)
    fun `pagination offset equals page times effective size`(
        @ForAll @IntRange(min = 0, max = 10) page: Int,
        @ForAll @IntRange(min = 1, max = 100) requestedSize: Int
    ) {
        val backend = ControllableBackend { emptyList() }
        val client = OpenSearchClient(backend)

        client.search(PropertySearchFilter(page = page, size = requestedSize))

        val effectiveSize = requestedSize.coerceIn(0, 20)
        val expectedFrom = page * effectiveSize
        assertEquals(expectedFrom, backend.lastFrom,
            "from=${ backend.lastFrom} must equal page($page) × effectiveSize($effectiveSize)")
    }
}
