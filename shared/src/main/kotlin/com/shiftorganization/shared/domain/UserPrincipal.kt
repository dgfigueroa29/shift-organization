package com.shiftorganization.shared.domain

import io.ktor.server.auth.*

data class UserPrincipal(
    val userId: String,
    val role: Role
) : Principal
