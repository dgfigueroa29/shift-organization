package com.shiftorganization.lambda.recurringprocessor.handler

import com.shiftorganization.lambda.recurringprocessor.recurringProcessorModule
import io.ktor.server.application.Application

class RecurringProcessorHandler {
    fun Application.module() = recurringProcessorModule()
}
