package com.shiftorganization.lambda.recurringevents.handler

import com.shiftorganization.lambda.recurringevents.recurringEventsModule
import io.ktor.server.application.Application

class RecurringEventsHandler {
    fun Application.module() = recurringEventsModule()
}
