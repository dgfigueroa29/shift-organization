package com.shiftorganization.lambda.properties

import io.ktor.server.application.Application

class PropertiesHandler {
    fun Application.module() = propertiesModule()
}
