plugins {
    id("buildsrc.convention.kotlin-jvm")
    kotlin("plugin.serialization") version "2.2.21"
}

// Pure models only: no HTTP, no database, no coroutines-heavy logic.
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}
