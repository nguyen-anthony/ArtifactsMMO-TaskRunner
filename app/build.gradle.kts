plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Apply the Application plugin to add support for building an executable JVM application.
    application

    // Kotlin serialization plugin for TaskStore persistence
    kotlin("plugin.serialization") version "2.2.21"
}

dependencies {
    // Project "app" depends on project "utils". (Project paths are separated with ":", so ":utils" refers to the top-level "utils" project.)
    implementation(project(":utils"))

    // Add the ArtifactsMMO client library (includes coroutines, datetime, and serialization via api)
    implementation(project(":client"))

    // Mordant for styled terminal output (tables, colors, formatting)
    implementation("com.github.ajalt.mordant:mordant:3.0.2")

    // dotenv for loading .env files
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.0")
}

application {
    // Define the Fully Qualified Name for the application main class
    mainClass = "com.artifactsmmo.app.MainKt"
}

// Connect stdin to the application so interactive terminal input works
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
