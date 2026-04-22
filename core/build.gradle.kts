plugins {
    id("buildsrc.convention.kotlin-jvm")
    kotlin("plugin.serialization") version "2.2.21"
}

dependencies {
    // Core depends on the API client (which pulls in coroutines, datetime, serialization)
    implementation(project(":client"))
    implementation(project(":utils"))
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")
}
