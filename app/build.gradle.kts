plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Apply the Application plugin to add support for building an executable JVM application.
    application
}

dependencies {
    // Project "app" depends on project "utils". (Project paths are separated with ":", so ":utils" refers to the top-level "utils" project.)
    implementation(project(":utils"))

    // Add the ArtifactsMMO client library (includes coroutines, datetime, and serialization via api)
    implementation(project(":client"))
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `BotExample.kt` to a class with FQN `com.nguyen_anthony.app.BotExampleKt`.)
    mainClass = "com.nguyen_anthony.app.GatherCopperKt"
}
