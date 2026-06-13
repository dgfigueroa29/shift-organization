package com.shiftorganization.lambda.notifications

import com.shiftorganization.shared.config.EnvironmentConfig
import com.shiftorganization.shared.notification.NotificationService
import com.shiftorganization.shared.observability.CloudWatchMetricEmitter
import com.shiftorganization.shared.observability.initXRay
import com.shiftorganization.shared.plugins.CorrelationIdKey
import com.shiftorganization.shared.plugins.CorrelationIdPlugin
import com.shiftorganization.shared.plugins.configureStatusPages
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient
import software.amazon.awssdk.services.ses.SesClient
import kotlin.time.Duration
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("NotificationsModule")

@Serializable
data class SnsEventRecord(
    val Sns: SnsMessage
)

@Serializable
data class SnsMessage(
    val Message: String
)

@Serializable
data class SnsLambdaEvent(
    val Records: List<SnsEventRecord>
)

fun Application.notificationsModule() {
    val config = EnvironmentConfig()
    val userPoolId = config.cognitoUserPoolId
        ?: error("COGNITO_USER_POOL_ID is required for the notifications handler")
    val senderAddress = config.sesSenderAddress
        ?: error("SES_SENDER_ADDRESS is required for the notifications handler")
    val metricEmitter = if (config.deploymentConfig.enableCloudWatchMetrics) {
        CloudWatchMetricEmitter.create(config.cloudWatchNamespace)
    } else null
    notificationsModule(
        NotificationService(
            sesClient = SesClient.builder().build(),
            cognitoClient = CognitoIdentityProviderClient.builder().build(),
            userPoolId = userPoolId,
            senderAddress = senderAddress,
            metricEmitter = metricEmitter
        )
    )
}

fun Application.notificationsModule(notificationService: NotificationService) {
    install(ContentNegotiation) { json() }
    install(CorrelationIdPlugin)
    configureStatusPages()

    val config = EnvironmentConfig()
    if (config.deploymentConfig.enableStructuredLogging) {
        install(CallLogging) {
            format { call ->
                val cid = call.attributes.getOrNull(CorrelationIdKey) ?: "unknown"
                val startTime = call.attributes.getOrNull(AttributeKey<Long>("startTime")) ?: System.currentTimeMillis()
                val latency = System.currentTimeMillis() - startTime
                """{"timestamp":"${java.time.Instant.now()}","correlationId":"$cid","method":"${call.request.httpMethod.value}","path":"${call.request.path()}","status":${call.response.status()?.value},"latencyMs":$latency}"""
            }
        }
    }

    routing {
        post("/notifications/sns") {
            val body = call.receiveText()
            notificationService.handleMessage(body)
            call.respond(HttpStatusCode.OK)
        }

        get("/notifications/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "healthy"))
        }
    }
}

fun main() {
    initXRay()
    logger.info("Lambda function notifications-module starting (runtime={})", if (System.getenv("NATIVE_IMAGE")?.toBoolean() == true) "native" else "jvm")
    io.ktor.server.netty.EngineMain.main(arrayOf())
}