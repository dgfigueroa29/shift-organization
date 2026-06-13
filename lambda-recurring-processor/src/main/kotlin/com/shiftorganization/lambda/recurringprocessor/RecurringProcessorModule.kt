package com.shiftorganization.lambda.recurringprocessor

import com.shiftorganization.shared.config.EnvironmentConfig
import com.shiftorganization.shared.db.DynamoDbFactory
import com.shiftorganization.shared.db.RecurringEventRepository
import com.shiftorganization.shared.db.WorkflowStateRepository
import com.shiftorganization.shared.notification.SnsPublisher
import com.shiftorganization.shared.observability.CloudWatchMetricEmitter
import com.shiftorganization.shared.observability.initXRay
import com.shiftorganization.shared.plugins.CorrelationIdKey
import com.shiftorganization.shared.plugins.CorrelationIdPlugin
import com.shiftorganization.shared.plugins.configureStatusPages
import com.shiftorganization.shared.service.RecurringEventProcessorService
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
import kotlin.time.Duration
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("RecurringProcessorModule")

@Serializable
data class ProcessRequest(val eventId: String)

@Serializable
data class ProcessResponse(val eventId: String, val status: String)

fun Application.recurringProcessorModule() {
    val config = EnvironmentConfig()
    val enhancedClient = DynamoDbFactory.createEnhancedClient()
    val recurringEventRepo = RecurringEventRepository(
        enhancedClient,
        config.dynamoTableRecurringEvents
    )
    val notificationPublisher = SnsPublisher.create(config)
    val metricEmitter = if (config.deploymentConfig.enableCloudWatchMetrics) {
        CloudWatchMetricEmitter.create(config.cloudWatchNamespace)
    } else null
    recurringProcessorModule(
        RecurringEventProcessorService(
            recurringEventRepo = recurringEventRepo,
            notificationPublisher = notificationPublisher,
            metricEmitter = metricEmitter
        )
    )
}

fun Application.recurringProcessorModule(processorService: RecurringEventProcessorService) {
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
        post("/process") {
            val request = call.receive<ProcessRequest>()
            processorService.process(request.eventId)
            call.respond(HttpStatusCode.OK, ProcessResponse(request.eventId, "processed"))
        }
    }
}

fun main() {
    initXRay()
    logger.info("Lambda function recurring-processor-module starting (runtime={})", if (System.getenv("NATIVE_IMAGE")?.toBoolean() == true) "native" else "jvm")
    io.ktor.server.netty.EngineMain.main(arrayOf())
}