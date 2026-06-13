package com.shiftorganization.shared.service

import com.shiftorganization.shared.model.PropertySearchFilter
import com.shiftorganization.shared.model.PagedResult
import com.shiftorganization.shared.model.PropertySummary
import com.shiftorganization.shared.search.OpenSearchClient

/**
 * Application service for property search operations.
 *
 * Delegates to the [OpenSearchClient] and handles mapping to the public API response.
 */
class PropertySearchService(
    private val openSearchClient: OpenSearchClient
) {
    fun search(filter: PropertySearchFilter): PagedResult<PropertySummary> {
        return openSearchClient.search(filter)
    }
}