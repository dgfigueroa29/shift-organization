package com.shiftorganization.shared.model

import kotlinx.serialization.Serializable

/**
 * Generic paginated response wrapper.
 *
 * @param T    The type of items in the page.
 * @param items The items on the current page (at most 20 for search results).
 * @param total Total number of matching items across all pages.
 * @param page  Zero-based page index that was requested.
 * @param size  Number of items requested per page.
 */
@Serializable
data class PagedResult<T>(
    val items: List<T>,
    val total: Long,
    val page: Int,
    val size: Int
)
