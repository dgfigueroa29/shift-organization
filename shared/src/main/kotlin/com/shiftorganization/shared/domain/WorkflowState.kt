package com.shiftorganization.shared.domain

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey

/**
 * DynamoDB-backed workflow state used to track multi-step operations.
 */
@DynamoDbBean
data class WorkflowState(
    @get:DynamoDbPartitionKey
    var workflowId: String = "",

    @get:DynamoDbSortKey
    var workflowType: String = "",

    var status: String = "",
    var currentStep: String = "",
    var errorDetails: String? = null,
    var version: Long = 0,
    var ttl: Long? = null,
    var createdAt: String = "",
    var updatedAt: String = ""
)
