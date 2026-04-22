package com.artifactsmmo.gui

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Properties

data class UpdateInfo(val version: String, val releaseUrl: String)

object UpdateChecker {

    private const val RELEASES_API =
        "https://api.github.com/repos/nguyen-anthony/ArtifactsMMO-TaskRunner/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String,
    )

    /** Returns the version string bundled into version.properties at build time, or null on error. */
    fun currentVersion(): String? = runCatching {
        val props = Properties()
        UpdateChecker::class.java.getResourceAsStream("/version.properties")
            ?.use { props.load(it) }
        props.getProperty("version")?.trim()
    }.getOrNull()

    /**
     * Checks GitHub for a newer release.  Fails silently — never throws.
     * Returns [UpdateInfo] if a different version is available, null otherwise.
     */
    suspend fun checkForUpdate(): UpdateInfo? = runCatching {
        val local = currentVersion() ?: return null

        val client = HttpClient(CIO)
        val responseText = client.use {
            it.get(RELEASES_API) {
                header(HttpHeaders.UserAgent, "ArtifactsMMO-TaskRunner")
                accept(ContentType.Application.Json)
            }.bodyAsText()
        }

        val release = json.decodeFromString<GitHubRelease>(responseText)
        val remote = release.tagName.trimStart('v')

        if (remote != local) UpdateInfo(version = remote, releaseUrl = release.htmlUrl) else null
    }.getOrNull()
}
