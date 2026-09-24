package com.artifactsmmo.core.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class CredentialStore(
    private val file: File = File(System.getProperty("user.home"), ".artifactsmmo/credentials.json")
) {
    @Serializable
    data class Credentials(val token: String, val username: String? = null)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): Credentials? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<Credentials>(file.readText()) }.getOrNull()
    }

    fun save(token: String, username: String?) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Credentials(token, username)))
    }

    fun clear() { file.delete() }
}
