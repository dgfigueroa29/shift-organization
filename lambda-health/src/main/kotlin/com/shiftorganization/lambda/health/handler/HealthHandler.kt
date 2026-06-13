package com.shiftorganization.lambda.health.handler

import com.shiftorganization.lambda.health.healthModule
import io.ktor.server.application.Application

class HealthHandler {
    fun Application.module() = healthModule()
}
