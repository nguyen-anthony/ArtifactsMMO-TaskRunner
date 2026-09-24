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
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig = ServerConfig(
            port = env["PORT"]?.toIntOrNull() ?: 8080,
            databaseUrl = env["DATABASE_URL"]?.takeIf { it.isNotBlank() },
            databaseUser = env["DATABASE_USER"]?.takeIf { it.isNotBlank() },
            databasePassword = env["DATABASE_PASSWORD"]?.takeIf { it.isNotBlank() },
            databaseSchema = env["DATABASE_SCHEMA"]?.takeIf { it.isNotBlank() } ?: "taskrunner",
            artifactsToken = env["ARTIFACTS_TOKEN"]?.takeIf { it.isNotBlank() },
            adminApiKey = env["ADMIN_API_KEY"]?.takeIf { it.isNotBlank() },
        )
    }
}
