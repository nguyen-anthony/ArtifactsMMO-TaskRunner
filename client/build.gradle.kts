plugins {
    id("buildsrc.convention.kotlin-jvm")
    kotlin("plugin.serialization") version "2.2.21"
}

dependencies {
    // HTTP client
    api("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-cio:3.1.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
    implementation("io.ktor:ktor-client-auth:3.1.3")
    implementation("io.ktor:ktor-client-logging:3.1.3")

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
    testImplementation("io.ktor:ktor-client-mock:3.1.3")
    testImplementation(kotlin("test"))
}

