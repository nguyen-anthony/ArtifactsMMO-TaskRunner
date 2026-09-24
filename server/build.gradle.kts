plugins {
    id("buildsrc.convention.kotlin-jvm")
    kotlin("plugin.serialization") version "2.2.21"
    application
}

val ktorVersion = "3.1.3"

dependencies {
    implementation(project(":domain"))
    implementation(project(":engine"))
    implementation(project(":client"))

    // HTTP server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // Database: Postgres (Supabase) via Hikari, schema managed by Flyway
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.flywaydb:flyway-core:11.3.1")
    implementation("org.flywaydb:flyway-database-postgresql:11.3.1")

    implementation("ch.qos.logback:logback-classic:1.5.16")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.artifactsmmo.server.MainKt")
}
