package com.shiftorganization.shared.db

import com.shiftorganization.shared.config.EnvironmentConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Builds and manages the database infrastructure for Shift Organization Lambda
 * deployment units.
 *
 * Responsibilities:
 * - Constructs a [HikariDataSource] from [EnvironmentConfig] credentials.
 * - Runs Flyway migrations to bring the schema to the current version.
 * - Connects Jetbrains Exposed to the data source via [Database.connect].
 *
 * Usage (Lambda cold-start):
 * ```kotlin
 * val db = DatabaseFactory.init(EnvironmentConfig())
 * ```
 */
object DatabaseFactory {

    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    /**
     * Initialises HikariCP, runs Flyway migrations (if enabled), and wires Exposed.
     *
     * @param config Application environment configuration.
     * @return The [Database] instance managed by Exposed (useful for testing).
     */
    fun init(config: EnvironmentConfig): Database {
        val dataSource = buildDataSource(config)
        if (config.deploymentConfig.runFlywayOnStartup) {
            runMigrations(dataSource)
        }
        return Database.connect(dataSource)
    }

    /**
     * Runs pending Flyway migrations found under
     * `classpath:db/migration` (Requirement 4.10 — atomic schema state).
     * Separated to allow explicit control in Lambda deployments.
     */
    fun runMigrations(config: EnvironmentConfig) {
        val dataSource = buildDataSource(config)
        runMigrations(dataSource)
    }

    /**
     * Builds a [HikariDataSource] sized for Lambda concurrency.
     *
     * Pool is intentionally small (max 10 connections) because each Lambda
     * instance handles requests serially; the pool exists primarily to avoid
     * the cost of per-request connection establishment (Requirement 10.3).
     * In AWS Lambda, lazy init is used so connections are established on first use.
     */
    fun buildDataSource(config: EnvironmentConfig): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.rdsJdbcUrl
            username = config.rdsUsername
            password = config.rdsPassword
            driverClassName = "org.postgresql.Driver"

            // Lambda-appropriate pool sizing
            maximumPoolSize = 10
            minimumIdle = if (config.deploymentConfig.dbConnectionLazy) 0 else 1
            idleTimeout = 300_000   // 5 minutes
            connectionTimeout = 10_000  // 10 seconds
            maxLifetime = 1_800_000 // 30 minutes

            // Keep-alive ping to prevent stale connections on Lambda warm-up
            keepaliveTime = 60_000    // 1 minute
            connectionTestQuery = "SELECT 1"
            initializationFailTimeout = if (config.deploymentConfig.dbConnectionLazy) -1 else 30_000

            poolName = "ShiftOrgPool"
        }
        logger.info("Creating HikariCP pool for ${config.rdsJdbcUrl} (lazy=${config.deploymentConfig.dbConnectionLazy})")
        return HikariDataSource(hikariConfig)
    }

    /**
     * Runs pending Flyway migrations found under
     * `classpath:db/migration` (Requirement 4.10 — atomic schema state).
     */
    private fun runMigrations(dataSource: DataSource) {
        logger.info("Running Flyway migrations")
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .load()
            .migrate()
        logger.info("Flyway migrations completed")
    }
}