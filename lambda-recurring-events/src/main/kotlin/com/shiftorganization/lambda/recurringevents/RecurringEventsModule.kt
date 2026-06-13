package com.shiftorganization.lambda.recurringevents

import com.shiftorganization.shared.auth.COGNITO_JWT_AUTH
import com.shiftorganization.shared.auth.cognitoJwt
import com.shiftorganization.shared.auth.requireRole
import com.shiftorganization.shared.config.EnvironmentConfig
import com.shiftorganization.shared.db.DatabaseFactory
import com.shiftorganization.shared.db.DynamoDbFactory
import com.shiftorganization.shared.db.PropertyRepository
import com.shiftorganization.shared.db.RecurringEventRepository
import com.shiftorganization.shared.db.WorkflowStateRepository
import com.shiftorganization.shared.domain.Role
import com.shiftorganization.shared.domain.UserPrincipal
import com.shiftorganization.shared.exception.NotFoundException
import com.shiftorganization.shared.exception.UnauthorizedException
import com.shiftorganization.shared.model.CreateRecurringEventRequest
import com.shiftorganization.shared.model.RecurringEventResponse
import com.shiftorganization.shared.observability.CloudWatchMetricEmitter
import com.shiftorganization.shared.observability.initXRay
import com.shiftorganization.shared.plugins.CorrelationIdKey
import com.shiftorganization.shared.plugins.CorrelationIdPlugin
import com.shiftorganization.shared.plugins.configureStatusPages
import com.shiftorganization.shared.scheduling.EventBridgeScheduler
import com.shiftorganization.shared.service.RecurringEventService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlin.time.Duration
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("RecurringEventsModule")

fun Application.recurringEventsModule() {
    val config = EnvironmentConfig()
    val database = DatabaseFactory.init(config)
    val enhancedClient = DynamoDbFactory.createEnhancedClient()
    val recurringEventRepo = RecurringEventRepository(
        enhancedClient,
        config.dynamoTableRecurringEvents
    )
    val workflowRepo = WorkflowStateRepository(
        enhancedClient,
        config.dynamoTableWorkflowState
    )
    val scheduler = EventBridgeScheduler.create(config.eventBridgeBusName)
    val metricEmitter = if (config.deploymentConfig.enableCloudWatchMetrics) {
        CloudWatchMetricEmitter.create(config.cloudWatchNamespace)
    } else null
    val recurringEventService = RecurringEventService(
        recurringEventRepo = recurringEventRepo,
        propertyRepo = PropertyRepository(database),
        workflowRepo = workflowRepo,
        scheduler = scheduler,
        metricEmitter = metricEmitter
    )
    install(Authentication) {
        cognitoJwt(config.cognitoJwksUri)
    }
    recurringEventsModule(recurringEventService = recurringEventService)
}

fun Application.recurringEventsModule(
    recurringEventService: RecurringEventService
) {
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
        authenticate(COGNITO_JWT_AUTH) {
            post("/recurring-events") {
                call.requireRole(Role.OWNER, Role.ADMIN)
                val request = call.receive<CreateRecurringEventRequest>()
                val principal = call.principal<UserPrincipal>()
                    ?: throw UnauthorizedException()
                val event = recurringEventService.create(request, principal)
                call.respond(HttpStatusCode.Created, event.toResponse())
            }

            delete("/recurring-events/{id}") {
                call.requireRole(Role.OWNER, Role.ADMIN)
                val eventId = call.parameters["id"]
                    ?: throw NotFoundException("", "RecurringEvent")
                val propertyId = call.request.queryParameters["propertyId"]
                    ?: throw NotFoundException("", "RecurringEvent")
                val principal = call.principal<UserPrincipal>()
                    ?: throw UnauthorizedException()
                recurringEventService.delete(propertyId, eventId, principal)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun com.shiftorganization.shared.domain.RecurringEvent.toResponse(): RecurringEventResponse =
    RecurringEventResponse(
        eventId = eventId,
        propertyId = propertyId,
        cronExpression = cronExpression,
        eventType = eventType,
        status = status,
        lastTriggeredAt = lastTriggeredAt,
        createdAt = createdAt
    )

fun main() {
    initXRay()
    logger.info("Lambda function recurring-events-module starting (runtime={})", if (System.getenv("NATIVE_IMAGE")?.toBoolean() == true) "native" else "jvm")
    io.ktor.server.netty.EngineMain.main(arrayOf())
}