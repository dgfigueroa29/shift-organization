package com.shiftorganization.shared.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EnvironmentConfigTest {

    @Test
    fun `construction does not require variables unused by the current lambda`() {
        val config = EnvironmentConfig(
            mapOf(
                "COGNITO_JWKS_URI" to "https://cognito-idp.us-east-1.amazonaws.com/pool/.well-known/jwks.json"
            )
        )

        assertEquals(
            "https://cognito-idp.us-east-1.amazonaws.com/pool/.well-known/jwks.json",
            config.cognitoJwksUri
        )
    }

    @Test
    fun `accessing a missing required variable throws a descriptive error`() {
        val config = EnvironmentConfig(emptyMap())

        val ex = assertThrows<IllegalStateException> {
            config.rdsJdbcUrl
        }

        assertEquals(
            "Required environment variable 'RDS_JDBC_URL' is missing or blank. " +
                    "Please set it in the Lambda function configuration before deploying.",
            ex.message
        )
    }
}
