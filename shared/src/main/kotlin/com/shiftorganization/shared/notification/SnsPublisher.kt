package com.shiftorganization.shared.notification

import com.shiftorganization.shared.config.EnvironmentConfig
import com.shiftorganization.shared.domain.Booking
import com.shiftorganization.shared.domain.RecurringEvent
import com.shiftorganization.shared.model.NotificationPayload
import kotlinx.serialization.json.Json
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishRequest
import java.time.Instant

class SnsPublisher(
    private val snsClient: SnsClient,
    private val topicArn: String
) : NotificationPublisher {

    private val json = Json { ignoreUnknownKeys = true }

    override fun publishBookingCreated(booking: Booking) {
        val payload = NotificationPayload(
            eventType = "BOOKING_CREATED",
            entityId = booking.id,
            timestamp = Instant.now().toString(),
            affectedUserIds = listOf(booking.tenantId)
        )
        publishBlocking(payload)
    }

    override fun publishBookingCancelled(booking: Booking) {
        val payload = NotificationPayload(
            eventType = "BOOKING_CANCELLED",
            entityId = booking.id,
            timestamp = Instant.now().toString(),
            affectedUserIds = listOf(booking.tenantId)
        )
        publishBlocking(payload)
    }

    override fun publishRecurringEventTriggered(event: RecurringEvent) {
        val payload = NotificationPayload(
            eventType = "RECURRING_EVENT_TRIGGERED",
            entityId = event.eventId,
            timestamp = Instant.now().toString(),
            affectedUserIds = emptyList()
        )
        publishBlocking(payload)
    }

    private fun publishBlocking(payload: NotificationPayload) {
        snsClient.publish(
            PublishRequest.builder()
                .topicArn(topicArn)
                .message(json.encodeToString(NotificationPayload.serializer(), payload))
                .build()
        )
    }

    companion object {
        fun create(config: EnvironmentConfig): SnsPublisher {
            val builder = SnsClient.builder()

            val deploy = config.deploymentConfig
            if (deploy.useLocalStack) {
                val endpoint = deploy.localstackEndpoint
                    ?: error("LOCALSTACK_ENDPOINT is required when USE_LOCALSTACK=true")
                builder.endpointOverride(java.net.URI.create(endpoint))
                    .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                            deploy.localstackAccessKey,
                            deploy.localstackSecretKey
                        )
                    ))
                    .region(software.amazon.awssdk.regions.Region.of(deploy.awsRegion))
            }

            if (deploy.enableSnsRetries) {
                builder.overrideConfiguration { config ->
                    config.retryPolicy(
                        RetryPolicy.builder()
                            .numRetries(3)
                            .build()
                    )
                }
            }

            val client = builder.build()
            return SnsPublisher(client, config.snsTopicArn)
        }
    }
}