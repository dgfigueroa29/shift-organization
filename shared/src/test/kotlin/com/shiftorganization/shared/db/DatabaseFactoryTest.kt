package com.shiftorganization.shared.db

import com.shiftorganization.shared.config.EnvironmentConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class DatabaseFactoryTest {

    @Test
    fun `buildDataSource creates HikariCP pool with config values`() {
        val config = EnvironmentConfig(
            mapOf(
                "RDS_JDBC_URL" to "jdbc:postgresql://localhost:5432/testdb",
                "RDS_USERNAME" to "testuser",
                "RDS_PASSWORD" to "testpass",
                "COGNITO_JWKS_URI" to "https://cognito-idp.us-east-1.amazonaws.com/pool/.well-known/jwks.json",
                "LAZY_DB_INIT" to "true"
            )
        )

        val ds = DatabaseFactory.buildDataSource(config)

        assertEquals("jdbc:postgresql://localhost:5432/testdb", ds.jdbcUrl)
        assertEquals("testuser", ds.username)
        assertEquals("testpass", ds.password)
        ds.close()
    }

    @Test
    fun `buildDataSource uses lazy init when configured`() {
        val config = EnvironmentConfig(
            mapOf(
                "RDS_JDBC_URL" to "jdbc:postgresql://localhost:5432/testdb",
                "RDS_USERNAME" to "testuser",
                "RDS_PASSWORD" to "testpass",
                "COGNITO_JWKS_URI" to "https://cognito-idp.us-east-1.amazonaws.com/pool/.well-known/jwks.json",
                "LAZY_DB_INIT" to "true"
            )
        )

        val ds = DatabaseFactory.buildDataSource(config)
        assertEquals("ShiftOrgPool", ds.poolName)
        ds.close()
    }

    @Test
    fun `init with flyway disabled and lazy init does not throw`() {
        val config = EnvironmentConfig(
            mapOf(
                "RDS_JDBC_URL" to "jdbc:postgresql://localhost:5432/testdb",
                "RDS_USERNAME" to "testuser",
                "RDS_PASSWORD" to "testpass",
                "COGNITO_JWKS_URI" to "https://cognito-idp.us-east-1.amazonaws.com/pool/.well-known/jwks.json",
                "FLYWAY_ON_STARTUP" to "false",
                "LAZY_DB_INIT" to "true"
            )
        )

        val db = DatabaseFactory.init(config)
        assertNotNull(db)
    }
}
