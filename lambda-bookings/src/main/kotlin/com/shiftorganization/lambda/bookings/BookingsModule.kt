package com.shiftorganization.lambda.bookings

import com.shiftorganization.shared.auth.COGNITO_JWT_AUTH
import com.shiftorganization.shared.auth.cognitoJwt
import com.shiftorganization.shared.auth.requireRole
import com.shiftorganization.shared.config.EnvironmentConfig
import com.shiftorganization.shared.db.BookingRepository
import com.shiftorganization.shared.db.DatabaseFactory
import com.shiftorganization.shared.db.DynamoDbFactory
import com.shiftorganization.shared.db.WorkflowStateRepository
import com.shiftorganization.shared.domain.Role
import com.shiftorganization.shared.domain.UserPrincipal
import com.shiftorganization.shared.exception.NotFoundException
import com.shiftorganization.shared.exception.UnauthorizedException
import com.shiftorganization.shared.model.BookingResponse
import com.shiftorganization.shared.model.CreateBookingRequest
import com.shiftorganization.shared.notification.SnsPublisher
import com.shiftorganization.shared.observability.CloudWatchMetricEmitter
import com.shiftorganization.shared.observability.initXRay
import com.shiftorganization.shared.plugins.CorrelationIdKey
import com.shiftorganization.shared.plugins.CorrelationIdPlugin
import com.shiftorganization.shared.plugins.configureStatusPages
import com.shiftorganization.shared.service.BookingService
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

private val logger = LoggerFactory.getLogger("BookingsModule")

fun Application.bookingsModule() {
    val config = EnvironmentConfig()
    val database = DatabaseFactory.init(config)
    val enhancedClient = DynamoDbFactory.createEnhancedClient()
    val bookingRepo = BookingRepository(database)
    val workflowRepo = WorkflowStateRepository(enhancedClient, config.dynamoTableWorkflowState)
    val publisher = SnsPublisher.create(config)
    val metricEmitter = if (config.deploymentConfig.enableCloudWatchMetrics) {
        CloudWatchMetricEmitter.create(config.cloudWatchNamespace)
    } else null
    val bookingService = BookingService(
        database            = database,
        bookingRepo         = bookingRepo,
        workflowRepo        = workflowRepo,
        notificationPublisher = publisher,
        metricEmitter       = metricEmitter
    )
    install(Authentication) {
        cognitoJwt(config.cognitoJwksUri)
    }
    bookingsModule(bookingService = bookingService)
}

fun Application.bookingsModule(
    bookingService: BookingService
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
            post("/bookings") {
                call.requireRole(Role.TENANT)
                val request = call.receive<CreateBookingRequest>()
                val principal = call.principal<UserPrincipal>()
                    ?: throw UnauthorizedException()
                val booking = bookingService.create(request, principal.userId)
                call.respond(HttpStatusCode.Created, booking.toResponse())
            }

            get("/bookings/{id}") {
                call.requireRole(Role.TENANT, Role.ADMIN)
                val id = call.parameters["id"]
                    ?: throw NotFoundException("", "Booking")
                val principal = call.principal<UserPrincipal>()
                    ?: throw UnauthorizedException()
                val booking = bookingService.findById(id, principal)
                call.respond(HttpStatusCode.OK, booking.toResponse())
            }

            delete("/bookings/{id}") {
                call.requireRole(Role.TENANT, Role.ADMIN)
                val id = call.parameters["id"]
                    ?: throw NotFoundException("", "Booking")
                val principal = call.principal<UserPrincipal>()
                    ?: throw UnauthorizedException()
                bookingService.cancel(id, principal)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun com.shiftorganization.shared.domain.Booking.toResponse(): BookingResponse =
    BookingResponse(
        id = id,
        propertyId = propertyId,
        tenantId = tenantId,
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        status = status,
        createdAt = createdAt.toString()
    )

fun main() {
    initXRay()
    logger.info("Lambda function bookings-module starting (runtime={})", if (System.getenv("NATIVE_IMAGE")?.toBoolean() == true) "native" else "jvm")
    io.ktor.server.netty.EngineMain.main(arrayOf())
}