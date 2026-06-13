package com.shiftorganization.lambda.properties.handler

import com.shiftorganization.lambda.properties.propertiesModule
import io.ktor.server.application.Application

/**
 * Lambda handler for Properties API.
 * 
 * For deployment to AWS Lambda, this can be used with:
 * 1. AWS Lambda Java runtime with a custom bootstrap (Ktor Netty)
 * 2. GraalVM native image with AWS Lambda custom runtime
 * 3. Container image with Ktor Netty
 * 
 * The actual HTTP routing is defined in [propertiesModule()].
 */
class PropertiesHandler {
    fun Application.module() = propertiesModule()
}