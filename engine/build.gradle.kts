plugins {
    id("buildsrc.convention.kotlin-jvm")
    kotlin("plugin.serialization") version "2.2.21"
}

dependencies {
    // Core depends on the API client (which pulls in coroutines, datetime, serialization)
    api(project(":client"))
    api(project(":domain"))
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(kotlin("test"))
}
