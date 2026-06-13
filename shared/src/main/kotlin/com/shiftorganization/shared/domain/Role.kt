package com.shiftorganization.shared.domain

/**
 * Cognito-assigned role attribute extracted from the `custom:role` JWT claim.
 * The three roles define the authorization boundaries across all API endpoints.
 */
enum class Role {
    ADMIN,
    OWNER,
    TENANT;

    companion object {
        /**
         * Case-insensitive parse of a raw claim string.
         * Throws [IllegalArgumentException] if the value is not a valid role.
         */
        fun fromClaim(value: String): Role =
            valueOf(value.uppercase())
    }
}
