plugins {
    // Use the same kotlin-jvm convention as other modules (avoids version conflict with buildSrc)
    id("buildsrc.convention.kotlin-jvm")

    // Compose Desktop plugins applied with explicit versions
    id("org.jetbrains.compose") version "1.10.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

dependencies {
    // Core task management and API client
    implementation(project(":core"))
    implementation(project(":client"))

    // Compose Desktop — includes compose runtime, UI, and foundation
    implementation(compose.desktop.currentOs)

    // Material 3
    implementation(compose.material3)

    // Coroutines for state management (including Swing dispatcher for Compose Desktop)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // Ktor client for image loading (character sprites)
    implementation("io.ktor:ktor-client-cio:3.1.3")

    // dotenv for loading .env files
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.0")
}

compose.desktop {
    application {
        mainClass = "com.artifactsmmo.gui.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe)
            packageName = "ArtifactsMMO"
            packageVersion = "1.0.0"
        }
    }
}

// Use project root as working directory so tasks.json is shared with :app.
afterEvaluate {
    tasks.named<JavaExec>("run") {
        workingDir = rootDir
    }
}
