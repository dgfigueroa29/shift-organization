package com.shiftorganization.lambda.bookings.handler

import com.shiftorganization.lambda.bookings.bookingsModule
import io.ktor.server.application.Application

class BookingsHandler {
    fun Application.module() = bookingsModule()
}
