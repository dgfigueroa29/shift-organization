package com.shiftorganization.lambda.properties

import com.shiftorganization.shared.auth.COGNITO_JWT_AUTH
import com.shiftorganization.shared.auth.cognitoJwt
import com.shiftorganization.shared.auth.requireRole
import com.shiftorganization.shared.config.EnvironmentConfig
import com.shiftorganization.shared.db.DatabaseFactory
import com.shiftorganization.shared.db.PropertyRepository
import com.shiftorganization.shared.domain.Role
import com.shiftorganization.shared.domain.UserPrincipal
import com.shiftorganization.shared.exception.BadRequestException
import com.shiftorganization.shared.exception.NotFoundException
import com.shiftorganization.shared.exception.UnauthorizedException
import com.shiftorganization.shared.model.CreatePropertyRequest
import com.shiftorganization.shared.model.PropertyResponse
import com.shiftorganization.shared.model.PropertySearchFilter
import com.shiftorganization.shared.observability.CloudWatchMetricEmitter
import com.shiftorganization.shared.observability.initXRay
import com.shiftorganization.shared.plugins.CorrelationIdPlugin
import com.shiftorganization.shared.plugins.configureStatusPages
import com.shiftorganization.shared.search.OpenSearchClient
import com.shiftorganization.shared.service.PropertySearchService
import com.shiftorganization.shared.service.PropertyService
import org.opensearch.client.opensearch.OpenSearchClient as OpenSearchSdkClient
import org.opensearch.client.transport.aws.AwsSdk2Transport
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import java.net.URI
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlin.time.Duration
import com.shiftorganization.shared.plugins.CorrelationIdKey
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PropertiesModule")

fun Application.propertiesModule() {
    val config = EnvironmentConfig()
    val database = DatabaseFactory.init(config)
    val openSearchClient = buildOpenSearchClient(config.openSearchEndpoint)
    val propertyRepo = PropertyRepository(database)
    val metricEmitter = if (config.deploymentConfig.enableCloudWatchMetrics) {
        CloudWatchMetricEmitter.create(config.cloudWatchNamespace)
    } else null
    val propertyService = PropertyService(
        propertyRepo = propertyRepo,
        searchClient = openSearchClient,
        scope = this,
        syncTimeoutMs = 5_000L,
        metricEmitter = metricEmitter
    )
    val propertySearchService = PropertySearchService(openSearchClient)
    install(Authentication) {
        cognitoJwt(config.cognitoJwksUri)
    }
    propertiesModule(
        propertyService = propertyService,
        propertySearchService = propertySearchService
    )
}

fun Application.propertiesModule(
    propertyService: PropertyService,
    propertySearchService: PropertySearchService
) {
    install(ContentNegotiation) { json() }
    install(CorrelationIdPlugin)
    configureStatusPages()

    val config = EnvironmentConfig()
    if (config.deploymentConfig.enableStructuredLogging) {
        install(CallLogging) {
            // Level is not directly accessible in Ktor 3.x, using default
            format { call ->
                val cid = call.attributes.getOrNull(CorrelationIdKey) ?: "unknown"
                val startTime = call.attributes.getOrNull(AttributeKey<Long>("startTime")) ?: System.currentTimeMillis()
                val latency = System.currentTimeMillis() - startTime
                """{"timestamp":"${java.time.Instant.now()}","correlationId":"$cid","method":"${call.request.httpMethod.value}","path":"${call.request.path()}","status":${call.response.status()?.value},"latencyMs":$latency}"""
            }
        }
    }
    if (config.deploymentConfig.enableHttpCompression) {
        install(Compression)
    }

    routing {
        authenticate(COGNITO_JWT_AUTH) {
            post("/properties") {
                call.requireRole(Role.OWNER, Role.ADMIN)
                val request = call.receive<CreatePropertyRequest>()
                val principal = call.principal<UserPrincipal>()
                    ?: throw UnauthorizedException()
                val property = propertyService.create(request, principal)
                call.respond(HttpStatusCode.Created, property.toResponse())
            }

            get("/properties/{id}") {
                val id = call.parameters["id"]
                    ?: throw BadRequestException("Missing path parameter 'id'")
                val property = propertyService.findById(id)
                    ?: throw NotFoundException(id, "Property")
                call.respond(HttpStatusCode.OK, property.toResponse())
            }

            put("/properties/{id}") {
                call.requireRole(Role.OWNER, Role.ADMIN)
                val id = call.parameters["id"]
                    ?: throw BadRequestException("Missing path parameter 'id'")
                val request = call.receive<CreatePropertyRequest>()
                val principal = call.principal<UserPrincipal>()
                    ?: throw UnauthorizedException()
                val property = propertyService.update(id, request, principal)
                call.respond(HttpStatusCode.OK, property.toResponse())
            }

            delete("/properties/{id}") {
                call.requireRole(Role.OWNER, Role.ADMIN)
                val id = call.parameters["id"]
                    ?: throw BadRequestException("Missing path parameter 'id'")
                val principal = call.principal<UserPrincipal>()
                    ?: throw UnauthorizedException()
                propertyService.delete(id, principal)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/properties/search") {
                val params = call.request.queryParameters
                val filter = PropertySearchFilter(
                    location  = params["location"],
                    priceMin  = params["priceMin"]?.toDoubleOrNull(),
                    priceMax  = params["priceMax"]?.toDoubleOrNull(),
                    available = params["available"]?.toBooleanStrictOrNull(),
                    page      = params["page"]?.toIntOrNull() ?: 0,
                    size      = params["size"]?.toIntOrNull() ?: 20
                )
                val result = propertySearchService.search(filter)
                call.respond(HttpStatusCode.OK, result)
            }
        }
    }
}

private fun buildOpenSearchClient(endpoint: String): OpenSearchClient {
    val host = runCatching { URI(endpoint).host ?: endpoint }.getOrDefault(endpoint)
    val region = System.getenv("AWS_REGION") ?: "us-east-1"
    val httpClient = ApacheHttpClient.builder().build()
    val transport = AwsSdk2Transport(
        httpClient,
        host,
        Region.of(region),
        AwsSdk2TransportOptions.builder().build()
    )
    return OpenSearchClient(OpenSearchSdkClient(transport))
}

private fun com.shiftorganization.shared.domain.Property.toResponse() = PropertyResponse(
    id           = id,
    ownerId      = ownerId,
    address      = address,
    description  = description,
    pricePerNight = pricePerNight,
    status       = status,
    createdAt    = createdAt.toString(),
    updatedAt    = updatedAt.toString()
)

fun main() {
    initXRay()
    logger.info("Lambda function properties-module starting (runtime={})", if (System.getenv("NATIVE_IMAGE")?.toBoolean() == true) "native" else "jvm")
    io.ktor.server.netty.EngineMain.main(arrayOf())
}