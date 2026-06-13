package com.shiftorganization.lambda.health

import com.shiftorganization.shared.config.EnvironmentConfig
import com.shiftorganization.shared.config.HealthAuthMode
import com.shiftorganization.shared.db.DatabaseFactory
import com.shiftorganization.shared.db.DynamoDbFactory
import com.shiftorganization.shared.observability.initXRay
import com.shiftorganization.shared.plugins.CorrelationIdKey
import com.shiftorganization.shared.plugins.CorrelationIdPlugin
import com.shiftorganization.shared.service.HealthProbe
import com.shiftorganization.shared.service.HealthService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration as JavaDuration
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

private val logger = LoggerFactory.getLogger("HealthModule")

private val rateLimitBuckets = ConcurrentHashMap<String, MutableList<Long>>()

// Shared HttpClient for OpenSearch probe
private val openSearchHttpClient = HttpClient.newBuilder()
    .connectTimeout(JavaDuration.ofSeconds(3))
    .build()

fun Application.healthModule() {
    val config = EnvironmentConfig()
    val database = DatabaseFactory.init(config)
    val dynamoClient = DynamoDbClient.builder().build()
    healthModule(
        HealthService(
            mapOf(
                "rds" to rdsProbe(database),
                "dynamodb" to dynamoProbe(dynamoClient),
                "opensearch" to openSearchProbe(config.openSearchEndpoint)
            )
        )
    )
}

fun Application.healthModule(healthService: HealthService) {
    install(ContentNegotiation) { json() }
    install(CorrelationIdPlugin)

    val config = EnvironmentConfig()
    if (config.deploymentConfig.enableStructuredLogging) {
        install(CallLogging) {
            format { call ->
                val cid = call.attributes.getOrNull(CorrelationIdKey) ?: "unknown"
                val startTime = call.attributes.getOrNull(AttributeKey<Long>("startTime")) ?: System.currentTimeMillis()
                val latency = System.currentTimeMillis() - startTime
                """{"timestamp":"${java.time.Instant.now()}","correlationId":"$cid","method":"${call.request.httpMethod}","path":"${call.request.path()}","status":${call.response.status()?.value},"latencyMs":$latency}"""
            }
        }
    }

    routing {
        get("/health") {
            val clientIp = call.request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
                ?: call.request.local.localHost
            if (isRateLimited(clientIp)) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf(
                    "error" to "RATE_LIMITED",
                    "message" to "Too many requests. Please try again later."
                ))
                return@get
            }
            val config = EnvironmentConfig()
            if (config.deploymentConfig.healthAuthMode == HealthAuthMode.STATIC_TOKEN) {
                val token = call.request.headers["X-Health-Token"]
                val expected = config.deploymentConfig.healthStaticToken ?: "health-token"
            if (token != expected) {
                val errorBody = mapOf(
                    "error" to "UNAUTHORIZED",
                    "message" to "Invalid health token",
                    "correlationId" to (call.attributes.getOrNull(CorrelationIdKey) ?: "unknown")
                )
                call.respond(HttpStatusCode.Unauthorized, errorBody)
                return@get
            }
            }
            val response = healthService.check()
            val status = if (response.status == "healthy") {
                HttpStatusCode.OK
            } else {
                HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, response)
        }
    }
}

private fun rdsProbe(database: Database): HealthProbe = HealthProbe {
    transaction(database) {
        exec("SELECT 1")
    }
}

private fun dynamoProbe(dynamoClient: DynamoDbClient): HealthProbe = HealthProbe {
    dynamoClient.listTables(ListTablesRequest.builder().limit(1).build())
}

private fun openSearchProbe(endpoint: String): HealthProbe = HealthProbe {
    val request = HttpRequest.newBuilder()
        .uri(URI.create("$endpoint/_cluster/health"))
        .timeout(JavaDuration.ofSeconds(5))
        .GET()
        .build()
    val response = openSearchHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() >= 500) {
        error("OpenSearch cluster health returned HTTP ${response.statusCode()}")
    }
}

private fun isRateLimited(clientIp: String, maxRequests: Int = 10, windowMs: Long = 60_000): Boolean {
    val now = System.currentTimeMillis()
    val timestamps = rateLimitBuckets.getOrPut(clientIp) { mutableListOf() }
    synchronized(timestamps) {
        timestamps.removeAll { now - it > windowMs }
        if (timestamps.size >= maxRequests) return true
        timestamps.add(now)
        return false
    }
}

fun main() {
    initXRay()
    logger.info("Lambda function health-module starting (runtime={})", if (System.getenv("NATIVE_IMAGE")?.toBoolean() == true) "native" else "jvm")
    io.ktor.server.netty.EngineMain.main(arrayOf())
}