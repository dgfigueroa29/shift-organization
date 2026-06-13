package com.shiftorganization.shared.db

import com.shiftorganization.shared.domain.RecurringEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import software.amazon.awssdk.core.pagination.sync.SdkIterable
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.enhanced.dynamodb.model.Page
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest

class RecurringEventRepositoryTest {

    private val enhancedClient = mock<DynamoDbEnhancedClient>()
    private val table = mock<DynamoDbTable<RecurringEvent>>()
    private val tableName = "recurring_events"

    init {
        whenever(
            enhancedClient.table(
                eq(tableName),
                any<software.amazon.awssdk.enhanced.dynamodb.TableSchema<RecurringEvent>>()
            )
        )
            .thenReturn(table)
    }

    @Test
    fun `put stores the recurring event`() {
        val repository = RecurringEventRepository(enhancedClient, tableName)
        val record = sampleEvent()

        val stored = repository.put(record)

        verify(table).putItem(record)
        assertEquals(record, stored)
    }

    @Test
    fun `find by property id returns all matching events`() {
        val repository = RecurringEventRepository(enhancedClient, tableName)
        val first = sampleEvent(eventId = "event-1")
        val second = sampleEvent(eventId = "event-2")

        whenever(table.query(any<QueryConditional>())).thenReturn(
            PageIterable.create(object : SdkIterable<Page<RecurringEvent>> {
                override fun iterator(): MutableIterator<Page<RecurringEvent>> =
                    mutableListOf(Page.builder(RecurringEvent::class.java).items(listOf(first, second)).build()).iterator()
            })
        )

        val results = repository.findByPropertyId("property-1")

        assertEquals(listOf(first, second), results)
    }

    @Test
    fun `delete removes the item by composite key`() {
        val repository = RecurringEventRepository(enhancedClient, tableName)

        repository.delete("property-1", "event-1")

        val keyCaptor = argumentCaptor<Key>()
        verify(table).deleteItem(keyCaptor.capture())
        assertEquals("property-1", keyCaptor.firstValue.partitionKeyValue().s())
        assertEquals("event-1", keyCaptor.firstValue.sortKeyValue().get().s())
    }

    @Test
    fun `set inactive updates the status when the record exists`() {
        val repository = RecurringEventRepository(enhancedClient, tableName)
        val existing = sampleEvent(status = "active")

        whenever(table.getItem(any<Key>())).thenReturn(existing)

        repository.setInactive("property-1", "event-1")

        val captor = argumentCaptor<RecurringEvent>()
        verify(table).putItem(captor.capture())
        assertEquals("inactive", captor.firstValue.status)
    }

    @Test
    fun `find by event id returns the matching event from the table scan`() {
        val repository = RecurringEventRepository(enhancedClient, tableName)
        val expected = sampleEvent(propertyId = "property-2", eventId = "event-2")
        whenever(table.scan(any<ScanEnhancedRequest>())).thenReturn(
            PageIterable.create(object : SdkIterable<Page<RecurringEvent>> {
                override fun iterator(): MutableIterator<Page<RecurringEvent>> =
                    mutableListOf(Page.builder(RecurringEvent::class.java).items(listOf(sampleEvent(eventId = "event-1"), expected)).build()).iterator()
            })
        )

        val result = repository.findByEventId("event-2")

        assertEquals(expected, result)
    }

    @Test
    fun `find by event id returns null when no event matches`() {
        val repository = RecurringEventRepository(enhancedClient, tableName)
        whenever(table.scan(any<ScanEnhancedRequest>())).thenReturn(
            PageIterable.create(object : SdkIterable<Page<RecurringEvent>> {
                override fun iterator(): MutableIterator<Page<RecurringEvent>> =
                    mutableListOf(Page.builder(RecurringEvent::class.java).items(listOf(sampleEvent(eventId = "event-1"))).build()).iterator()
            })
        )

        val result = repository.findByEventId("event-missing")

        assertNull(result)
    }

    @Test
    fun `mark triggered updates lastTriggeredAt when the record exists`() {
        val repository = RecurringEventRepository(enhancedClient, tableName)
        val existing = sampleEvent()
        whenever(table.getItem(any<Key>())).thenReturn(existing)

        repository.markTriggered("property-1", "event-1", "2026-06-09T10:15:30Z")

        val captor = argumentCaptor<RecurringEvent>()
        verify(table).putItem(captor.capture())
        assertEquals("2026-06-09T10:15:30Z", captor.firstValue.lastTriggeredAt)
    }

    private fun sampleEvent(
        propertyId: String = "property-1",
        eventId: String = "event-1",
        status: String = "active"
    ) = RecurringEvent(
        propertyId = propertyId,
        eventId = eventId,
        cronExpression = "cron(0 9 ? * MON *)",
        eventType = "MAINTENANCE",
        status = status,
        lastTriggeredAt = null,
        createdAt = "2026-06-04T00:00:00Z"
    )
}
