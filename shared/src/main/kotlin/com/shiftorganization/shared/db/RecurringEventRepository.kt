package com.shiftorganization.shared.db

import com.shiftorganization.shared.domain.RecurringEvent
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.enhanced.dynamodb.TableSchema
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest

/**
 * DynamoDB repository for recurring event records.
 */
class RecurringEventRepository(
    private val enhancedClient: DynamoDbEnhancedClient,
    tableName: String
) {
    private val table: DynamoDbTable<RecurringEvent> =
        enhancedClient.table(tableName, TableSchema.fromBean(RecurringEvent::class.java))

    fun put(record: RecurringEvent): RecurringEvent {
        table.putItem(record)
        return record
    }

    fun delete(propertyId: String, eventId: String) {
        table.deleteItem(
            Key.builder()
                .partitionValue(propertyId)
                .sortValue(eventId)
                .build()
        )
    }

    fun findByPropertyId(propertyId: String): List<RecurringEvent> =
        table.query(
            QueryConditional.keyEqualTo(
                Key.builder()
                    .partitionValue(propertyId)
                    .build()
            )
        ).flatMap { it.items() }

    fun findByEventId(eventId: String): RecurringEvent? =
        table.scan(ScanEnhancedRequest.builder().build())
            .items()
            .firstOrNull { it.eventId == eventId }

    fun setInactive(propertyId: String, eventId: String) {
        val existing = table.getItem(
            Key.builder()
                .partitionValue(propertyId)
                .sortValue(eventId)
                .build()
        ) ?: return

        existing.status = "inactive"
        table.putItem(existing)
    }

    fun markTriggered(propertyId: String, eventId: String, timestamp: String) {
        val existing = table.getItem(
            Key.builder()
                .partitionValue(propertyId)
                .sortValue(eventId)
                .build()
        ) ?: return

        existing.lastTriggeredAt = timestamp
        table.putItem(existing)
    }
}
