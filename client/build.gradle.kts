plugins {
    id("buildsrc.convention.kotlin-jvm")
    kotlin("plugin.serialization") version "2.2.21"
}

dependencies {
    // HTTP client
    api("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("io.ktor:ktor-client-auth:2.3.7")
    implementation("io.ktor:ktor-client-logging:2.3.7")

    // Serialization - expose to consumers for model serialization
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Coroutines - expose to consumers
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // DateTime - expose to consumers for model deserialization
    api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation("io.ktor:ktor-client-mock:2.3.7")
    testImplementation(kotlin("test"))
}

