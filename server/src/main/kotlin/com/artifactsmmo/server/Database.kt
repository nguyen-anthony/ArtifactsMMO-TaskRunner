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
