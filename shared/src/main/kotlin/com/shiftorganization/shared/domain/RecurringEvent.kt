package com.shiftorganization.shared.domain

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey

/**
 * DynamoDB-backed domain model for a recurring scheduled event linked to a property.
 *
 * Maps to the `recurring_events` table:
 * - Partition key: [propertyId]
 * - Sort key:      [eventId]
 *
 * The [DynamoDbBean] annotation instructs the DynamoDB Enhanced Client to use
 * the JavaBeans convention (no-arg constructor + getter/setter) for marshalling.
 * Kotlin `var` properties with default values satisfy this contract.
 */
@DynamoDbBean
data class RecurringEvent(
    @get:DynamoDbPartitionKey
    var propertyId: String = "",

    @get:DynamoDbSortKey
    var eventId: String = "",

    var cronExpression: String = "",
    var eventType: String = "",

    /** One of: `active`, `inactive` */
    var status: String = "active",

    /** ISO-8601 timestamp of the last EventBridge invocation, nullable. */
    var lastTriggeredAt: String? = null,

    /** ISO-8601 timestamp when this record was created. */
    var createdAt: String = ""
)
