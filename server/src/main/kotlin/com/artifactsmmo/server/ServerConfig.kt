package com.artifactsmmo.server

/**
 * All runtime configuration comes from environment variables (a `.env` file on the VPS,
 * loaded by Docker Compose). Nothing secret is ever committed.
 */
data class ServerConfig(
    val port: Int,
    /** JDBC URL, e.g. jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres
     *  Must be the session pooler (5432) or direct connection: LISTEN/NOTIFY needs a session. */
    val databaseUrl: String?,
    val databaseUser: String?,
    val databasePassword: String?,
    /** Postgres schema that holds all our tables (keeps us out of Supabase's `public`). */
    val databaseSchema: String,
    /** ArtifactsMMO API token. */
    val artifactsToken: String?,
    /** Static key the web UI exchanges for a session cookie. */
    val adminApiKey: String?,
    /** Mark the session cookie Secure (HTTPS only). Disable only for local http testing. */
    val cookieSecure: Boolean = true,
    /** Game API base URL; override to run against the beta/sandbox servers. */
    val artifactsApiUrl: String = DEFAULT_API_URL,
    /** Realtime WebSocket URL matching [artifactsApiUrl]. */
    val artifactsRealtimeUrl: String = DEFAULT_REALTIME_URL,
) {
    companion object {
        const val DEFAULT_API_URL = "https://api.artifactsmmo.com"
        const val DEFAULT_REALTIME_URL = "wss://realtime.artifactsmmo.com"

        /**
         * Reads `KEY=value` lines from a `.env` file (for local `./gradlew :server:run`).
         * Blank lines and `#` comments are skipped; surrounding quotes are stripped.
         */
        fun loadDotEnv(file: java.io.File = java.io.File(".env")): Map<String, String> {
            if (!file.isFile) return emptyMap()
            return file.readLines().mapNotNull { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@mapNotNull null
                val key = line.substringBefore('=').trim().removePrefix("export ").trim()
                val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
                key to value
            }.toMap()
        }

        /** Real environment variables win over `.env` values. */
        fun fromEnvironment(): ServerConfig = fromEnv(loadDotEnv() + System.getenv())

        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig = ServerConfig(
            port = env["PORT"]?.toIntOrNull() ?: 8080,
            databaseUrl = env["DATABASE_URL"]?.takeIf { it.isNotBlank() },
            databaseUser = env["DATABASE_USER"]?.takeIf { it.isNotBlank() },
            databasePassword = env["DATABASE_PASSWORD"]?.takeIf { it.isNotBlank() },
            databaseSchema = env["DATABASE_SCHEMA"]?.takeIf { it.isNotBlank() } ?: "taskrunner",
            artifactsToken = env["ARTIFACTS_TOKEN"]?.takeIf { it.isNotBlank() },
            adminApiKey = env["ADMIN_API_KEY"]?.takeIf { it.isNotBlank() },
            cookieSecure = env["COOKIE_SECURE"]?.lowercase() != "false",
            artifactsApiUrl = env["ARTIFACTS_API_URL"]?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: DEFAULT_API_URL,
            artifactsRealtimeUrl = env["ARTIFACTS_REALTIME_URL"]?.takeIf { it.isNotBlank() } ?: DEFAULT_REALTIME_URL,
        )
    }
}
