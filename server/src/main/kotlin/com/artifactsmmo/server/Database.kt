package com.artifactsmmo.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

object Database {
    fun connect(config: ServerConfig): HikariDataSource {
        val url = requireNotNull(config.databaseUrl) { "DATABASE_URL is not set" }
        val hikari = HikariConfig().apply {
            jdbcUrl = url
            username = config.databaseUser
            password = config.databasePassword
            schema = config.databaseSchema
            maximumPoolSize = 10
            poolName = "taskrunner"
        }
        return HikariDataSource(hikari)
    }

    /**
     * A standalone (non-pooled) connection for LISTEN and the advisory lock, which must
     * stay on one session for their whole lifetime.
     */
    fun sessionConnection(config: ServerConfig): java.sql.Connection {
        val props = java.util.Properties().apply {
            config.databaseUser?.let { setProperty("user", it) }
            config.databasePassword?.let { setProperty("password", it) }
            setProperty("currentSchema", config.databaseSchema)
        }
        return java.sql.DriverManager.getConnection(requireNotNull(config.databaseUrl), props)
    }

    /** Applies migrations from `server/src/main/resources/db/migration`. */
    fun migrate(dataSource: DataSource, schema: String) {
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .load()
            .migrate()
    }
}
