// The settings file is the entry point of every Gradle build.
// Its primary purpose is to define the subprojects.
// It is also used for some aspects of project-wide configuration, like managing plugins, dependencies, etc.
// https://docs.gradle.org/current/userguide/settings_file_basics.html

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Use Maven Central as the default repository (where Gradle will download dependencies) in all subprojects.
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

// Include all subprojects in the build.
//   client — typed HTTP/WebSocket wrapper for the ArtifactsMMO API
//   domain — pure, serializable models shared by engine and server (no I/O)
//   engine — game logic: gateway, executors, optimizer, queue/orchestrator, workers
//   server — Ktor HTTP app: REST + SSE, auth, database, wiring, main()
include(":client")
include(":domain")
include(":engine")
include(":server")

rootProject.name = "ArtifactsMMO"
