package com.shiftorganization.shared.db

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * Factory that builds the DynamoDB clients used by Lambda deployment units.
 *
 * The [DynamoDbClient] is configured from the Lambda execution environment
 * (region is auto-detected from the `AWS_REGION` env var set by the Lambda
 * runtime). The [DynamoDbEnhancedClient] wraps the base client and provides
 * the type-safe enhanced API used by [RecurringEventRepository] and
 * [WorkflowStateRepository].
 */
object DynamoDbFactory {

    /**
     * Creates a [DynamoDbEnhancedClient] backed by the default [DynamoDbClient].
     *
     * The underlying [DynamoDbClient] uses the AWS SDK's default credential
     * provider chain (Lambda execution role, environment variables, etc.) and
     * reads the region from the `AWS_REGION` environment variable injected by
     * the Lambda runtime.
     */
    fun createEnhancedClient(): DynamoDbEnhancedClient {
        val dynamoDbClient = DynamoDbClient.builder().build()
        return DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build()
    }
}
