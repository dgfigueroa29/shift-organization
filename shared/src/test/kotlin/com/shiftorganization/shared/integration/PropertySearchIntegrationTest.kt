package com.shiftorganization.shared.integration

import com.shiftorganization.shared.domain.Property
import com.shiftorganization.shared.model.PropertySearchFilter
import com.shiftorganization.shared.search.OpenSearchClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.opensearch.client.json.jackson.JacksonJsonpMapper
import org.opensearch.client.opensearch.OpenSearchClient as OpenSearchSdkClient
import org.opensearch.client.opensearch.indices.CreateIndexRequest
import org.opensearch.client.opensearch.indices.DeleteIndexRequest
import org.opensearch.client.opensearch.indices.RefreshRequest
import org.opensearch.client.transport.rest_client.RestClientTransport
import org.opensearch.testcontainers.OpensearchContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.apache.http.HttpHost
import org.opensearch.client.RestClient
import java.time.OffsetDateTime

/**
 * Integration tests for [OpenSearchClient] against a real OpenSearch instance via Testcontainers.
 *
 * Covers:
 * - Property metadata is searchable after indexing + refresh (Requirement 2.11)
 * - Price-range filter returns only properties within the range (Requirement 3.2)
 * - Availability filter returns only available properties (Requirement 3.3)
 * - Deleted property is removed from search results (Requirement 2.11)
 * - Page size is capped at 20 (Requirement 3.5)
 * - Location full-text search on address (Requirement 3.4)
 * - Combined filters narrow results correctly
 */
@Tag("integration")
@Testcontainers
class PropertySearchIntegrationTest {

    companion object {
        private const val INDEX = "properties"

        @Container
        @JvmStatic
        val opensearch: OpensearchContainer<*> = OpensearchContainer(
            DockerImageName.parse("opensearchproject/opensearch:2.11.1")
        )
    }

    private lateinit var client: OpenSearchClient
    private lateinit var sdkClient: OpenSearchSdkClient

    @BeforeEach
    fun setUp() {
        val restClient = RestClient.builder(
            HttpHost(opensearch.host, opensearch.firstMappedPort, "https")
        ).setHttpClientConfigCallback { builder ->
            builder.setSSLContext(
                javax.net.ssl.SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(TrustAllX509TrustManager), java.security.SecureRandom())
                }
            )
        }.build()

        val transport = RestClientTransport(restClient, JacksonJsonpMapper())
        sdkClient = OpenSearchSdkClient(transport)
        client = OpenSearchClient(sdkClient)

        // Fresh index per test
        runCatching {
            sdkClient.indices().delete(DeleteIndexRequest.Builder().index(INDEX).build())
        }
        sdkClient.indices().create(
            CreateIndexRequest.Builder()
                .index(INDEX)
                .mappings { m ->
                    m.properties("id")         { p -> p.keyword { it } }
                     .properties("ownerId")     { p -> p.keyword { it } }
                     .properties("address")     { p -> p.text { it } }
                     .properties("description") { p -> p.text { it } }
                     .properties("priceNight")  { p -> p.float_ { it } }
                     .properties("status")      { p -> p.keyword { it } }
                     .properties("updatedAt")   { p -> p.date { it } }
                }
                .build()
        )
    }

    private fun refresh() {
        sdkClient.indices().refresh(RefreshRequest.Builder().index(INDEX).build())
    }

    private fun prop(
        id: String,
        address: String = "123 Main St",
        price: Double = 100.0,
        status: String = "available"
    ) = Property(
        id = id, ownerId = "owner-1", address = address,
        description = "Test property", pricePerNight = price, status = status,
        createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z")
    )

    // -------------------------------------------------------------------------
    // Requirement 2.11 – indexed property is searchable
    // -------------------------------------------------------------------------

    @Test
    fun `indexed property is searchable after refresh`() {
        client.indexProperty(prop("prop-1", address = "Baker Street 221B"))
        refresh()

        val results = client.search(PropertySearchFilter(location = "Baker"))

        assertEquals(1, results.total)
        assertEquals("prop-1", results.items[0].id)
    }

    // -------------------------------------------------------------------------
    // Requirement 3.2 – price range filter
    // -------------------------------------------------------------------------

    @Test
    fun `price range filter returns only properties within the range`() {
        client.indexProperty(prop("cheap",     price = 50.0))
        client.indexProperty(prop("mid",       price = 120.0))
        client.indexProperty(prop("expensive", price = 300.0))
        refresh()

        val results = client.search(PropertySearchFilter(priceMin = 100.0, priceMax = 150.0))

        assertEquals(1, results.total)
        assertEquals("mid", results.items[0].id)
    }

    // -------------------------------------------------------------------------
    // Requirement 3.3 – availability filter
    // -------------------------------------------------------------------------

    @Test
    fun `availability filter returns only available properties`() {
        client.indexProperty(prop("avail1",  status = "available"))
        client.indexProperty(prop("avail2",  status = "available"))
        client.indexProperty(prop("unavail", status = "unavailable"))
        refresh()

        val results = client.search(PropertySearchFilter(available = true))

        assertEquals(2, results.total)
        assertTrue(results.items.all { it.status == "available" })
    }

    // -------------------------------------------------------------------------
    // Requirement 2.11 – deleted property is removed from index
    // -------------------------------------------------------------------------

    @Test
    fun `deleted property is removed from search results`() {
        client.indexProperty(prop("del-me"))
        refresh()
        assertEquals(1, client.search(PropertySearchFilter()).total)

        client.deleteProperty("del-me")
        refresh()

        assertEquals(0, client.search(PropertySearchFilter()).total)
    }

    // -------------------------------------------------------------------------
    // Requirement 3.5 – page size capped at 20
    // -------------------------------------------------------------------------

    @Test
    fun `search result page is capped at 20 items`() {
        (1..25).forEach { i -> client.indexProperty(prop("p-$i", address = "Street $i")) }
        refresh()

        val results = client.search(PropertySearchFilter(size = 100))

        assertTrue(results.items.size <= 20, "items.size ${results.items.size} must be ≤ 20")
        assertEquals(20, results.size)
    }

    // -------------------------------------------------------------------------
    // Requirement 3.4 – location full-text search
    // -------------------------------------------------------------------------

    @Test
    fun `location filter performs full-text match on address field`() {
        client.indexProperty(prop("park",  address = "Central Park Avenue"))
        client.indexProperty(prop("river", address = "Riverside Drive"))
        client.indexProperty(prop("ocean", address = "Ocean Boulevard"))
        refresh()

        val results = client.search(PropertySearchFilter(location = "Park"))

        assertTrue(results.items.any { it.id == "park" })
    }

    // -------------------------------------------------------------------------
    // Combined filters
    // -------------------------------------------------------------------------

    @Test
    fun `combined price and availability filter narrows results correctly`() {
        client.indexProperty(prop("a1", price = 80.0,  status = "available"))
        client.indexProperty(prop("a2", price = 120.0, status = "available"))
        client.indexProperty(prop("u1", price = 90.0,  status = "unavailable"))
        client.indexProperty(prop("a3", price = 200.0, status = "available"))
        refresh()

        val results = client.search(
            PropertySearchFilter(priceMin = 70.0, priceMax = 130.0, available = true)
        )

        assertEquals(2, results.total)
        val ids = results.items.map { it.id }
        assertTrue(ids.contains("a1"))
        assertTrue(ids.contains("a2"))
    }

    // -------------------------------------------------------------------------
    // Helper: trust-all TrustManager for test SSL (OpenSearch container uses self-signed cert)
    // -------------------------------------------------------------------------

    private object TrustAllX509TrustManager : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(
            chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }
}
