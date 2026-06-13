package com.shiftorganization.lambda.notifications.handler

import com.shiftorganization.lambda.notifications.notificationsModule
import io.ktor.server.application.Application

class NotificationsHandler {
    fun Application.module() = notificationsModule()
}
