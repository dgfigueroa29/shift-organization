package com.shiftorganization.shared.service

import com.shiftorganization.shared.model.PagedResult
import com.shiftorganization.shared.model.PropertySearchFilter
import com.shiftorganization.shared.model.PropertySummary
import com.shiftorganization.shared.search.OpenSearchClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PropertySearchServiceTest {

    private val openSearchClient = mock<OpenSearchClient>()
    private val service = PropertySearchService(openSearchClient)

    @Test
    fun `search delegates to OpenSearchClient and returns its result`() {
        val filter = PropertySearchFilter(location = "New York", page = 0, size = 10)
        val expected = PagedResult(
            items = listOf(PropertySummary(
                id = "p1",
                ownerId = "owner-1",
                address = "123 Main St",
                description = "Nice place",
                pricePerNight = 150.0,
                status = "available",
                updatedAt = "2026-01-01T00:00:00Z"
            )),
            total = 1,
            page = 0,
            size = 10
        )
        whenever(openSearchClient.search(any())).thenReturn(expected)

        val result = service.search(filter)

        assertEquals(expected, result)
        verify(openSearchClient).search(filter)
    }

    @Test
    fun `search passes empty filter correctly`() {
        val filter = PropertySearchFilter()
        val expected = PagedResult<PropertySummary>(items = emptyList(), total = 0, page = 0, size = 20)
        whenever(openSearchClient.search(any())).thenReturn(expected)

        val result = service.search(filter)

        assertEquals(expected, result)
        verify(openSearchClient).search(filter)
    }
}
