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

// ?? Sandbox integration tests ????????????????????????????????????????????????
// Live tests against api.sandbox.artifactsmmo.com (membership required). Kept out of
// `test`/`check`/`build` so CI never runs them. Run manually:  ./gradlew :engine:integrationTest
val integrationTest: SourceSet by sourceSets.creating
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
kotlin.target.compilations.getByName("integrationTest")
    .associateWith(kotlin.target.compilations.getByName("main"))

dependencies {
    "integrationTestImplementation"(kotlin("test-junit5"))
    "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.register<Test>("integrationTest") {
    description = "Runs live integration tests against the ArtifactsMMO sandbox server."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    // Sandbox characters and cooldowns are shared state: never run test classes in parallel.
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    workingDir = rootProject.projectDir
    testLogging { showStandardStreams = true }
    providers.gradleProperty("sandbox.provision").orNull?.let { systemProperty("sandbox.provision", it) }
}
