package com.shiftorganization.shared.search

import com.shiftorganization.shared.domain.Property
import com.shiftorganization.shared.exception.OpenSearchUnavailableException
import com.shiftorganization.shared.model.PagedResult
import com.shiftorganization.shared.model.PropertySearchFilter
import com.shiftorganization.shared.model.PropertySummary
import com.shiftorganization.shared.observability.CircuitBreaker
import org.opensearch.client.json.JsonData
import org.opensearch.client.opensearch._types.FieldValue
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery
import org.opensearch.client.opensearch._types.query_dsl.Query
import org.opensearch.client.opensearch.core.DeleteRequest
import org.opensearch.client.opensearch.core.IndexRequest
import org.opensearch.client.opensearch.core.SearchRequest
import org.opensearch.client.opensearch.core.SearchResponse
import java.io.IOException
import org.opensearch.client.opensearch.OpenSearchClient as OpenSearchSdkClient

/**
 * Wrapper around the generated OpenSearch client used by the property search flow.
 *
 * The wrapper keeps request/response mapping in one place and converts transport
 * failures into [OpenSearchUnavailableException] so the Ktor layer can respond
 * with HTTP 503 consistently.
 */
class OpenSearchClient constructor(
    private val backend: Backend,
    private val circuitBreaker: CircuitBreaker = CircuitBreaker("opensearch")
) {

    constructor(delegate: OpenSearchSdkClient) : this(SdkBackend(delegate))

    companion object {
        private const val INDEX_NAME = "properties"
    }

    fun indexProperty(property: Property) {
        circuitBreaker.call {
            runCatching {
                backend.indexProperty(property.toDocument())
            }.wrapUnavailable()
        }
    }

    fun deleteProperty(id: String) {
        circuitBreaker.call {
            runCatching {
                backend.deleteProperty(id)
            }.wrapUnavailable()
        }
    }

    fun search(filter: PropertySearchFilter): PagedResult<PropertySummary> {
        val requestedSize = filter.size.coerceIn(0, 20)
        val from = filter.page.coerceAtLeast(0) * requestedSize
        val query = buildQuery(filter)

        return circuitBreaker.call {
            runCatching {
                backend.search(query, from, requestedSize)
            }.fold(
                onSuccess = { page ->
                    PagedResult(
                        items = page.hits.map { it.toSummary() },
                        total = page.total,
                        page = filter.page.coerceAtLeast(0),
                        size = requestedSize
                    )
                },
                onFailure = { throw it.toUnavailable() }
            )
        }
    }

    interface Backend {
        fun indexProperty(document: Map<String, Any?>)
        fun deleteProperty(id: String)
        fun search(query: Query, from: Int, size: Int): SearchPage
    }

    data class SearchPage(
        val hits: List<Map<String, Any?>>,
        val total: Long
    )

    private class SdkBackend(
        private val client: OpenSearchSdkClient
    ) : Backend {
        override fun indexProperty(document: Map<String, Any?>) {
            val request = IndexRequest.of<Map<String, Any?>> {
                it.index(INDEX_NAME)
                    .id(document["id"] as String)
                    .document(document)
            }
            client.index(request)
        }

        override fun deleteProperty(id: String) {
            val request = DeleteRequest.of {
                it.index(INDEX_NAME).id(id)
            }
            client.delete(request)
        }

        override fun search(query: Query, from: Int, size: Int): SearchPage {
            val request = SearchRequest.of {
                it.index(INDEX_NAME)
                    .from(from)
                    .size(size)
                    .query(query)
            }

            @Suppress("UNCHECKED_CAST")
            val response = client.search(request, Map::class.java) as SearchResponse<Map<String, Any?>>
            return SearchPage(
                hits = response.hits().hits().mapNotNull { it.source() },
                total = response.hits().total()?.value() ?: response.hits().hits().size.toLong()
            )
        }
    }

    private fun Property.toDocument(): Map<String, Any?> = mapOf(
        "id" to id,
        "ownerId" to ownerId,
        "address" to address,
        "description" to description,
        "priceNight" to pricePerNight,
        "status" to status,
        "updatedAt" to updatedAt.toString()
    )

    private fun Map<String, Any?>.toSummary(): PropertySummary = PropertySummary(
        id = requireString("id"),
        ownerId = requireString("ownerId"),
        address = requireString("address"),
        description = this["description"]?.toString(),
        pricePerNight = requireNumber("priceNight").toDouble(),
        status = requireString("status"),
        updatedAt = requireString("updatedAt")
    )

    private fun buildQuery(filter: PropertySearchFilter): Query {
        val mustClauses = mutableListOf<Query>()
        val filterClauses = mutableListOf<Query>()

        filter.location?.takeIf { it.isNotBlank() }?.let { location ->
            mustClauses += Query.of {
                it.match { match ->
                    match.field("address").query(FieldValue.of(location))
                }
            }
        }

        filter.priceMin?.let { min ->
            filterClauses += Query.of {
                it.range { range ->
                    range.field("priceNight").gte(JsonData.of(min))
                }
            }
        }

        filter.priceMax?.let { max ->
            filterClauses += Query.of {
                it.range { range ->
                    range.field("priceNight").lte(JsonData.of(max))
                }
            }
        }

        if (filter.available == true) {
            filterClauses += Query.of {
                it.term { term ->
                    term.field("status").value(FieldValue.of("available"))
                }
            }
        }

        return Query.of {
            it.bool(
                BoolQuery.of { bool ->
                    bool.must(mustClauses).filter(filterClauses)
                }
            )
        }
    }

    private fun <T> runCatching(block: () -> T): Result<T> = kotlin.runCatching(block)

    private fun <T> Result<T>.wrapUnavailable(): T = fold(
        onSuccess = { it },
        onFailure = { throw it.toUnavailable() }
    )

    private fun Throwable.toUnavailable(): OpenSearchUnavailableException =
        when (this) {
            is OpenSearchUnavailableException -> this
            is IOException -> OpenSearchUnavailableException(cause = this)
            else -> OpenSearchUnavailableException(cause = this)
        }

    private fun Map<String, Any?>.requireString(name: String): String =
        this[name]?.toString() ?: error("OpenSearch document is missing field '$name'")

    private fun Map<String, Any?>.requireNumber(name: String): Number =
        this[name] as? Number ?: error("OpenSearch document field '$name' is not numeric")
}
