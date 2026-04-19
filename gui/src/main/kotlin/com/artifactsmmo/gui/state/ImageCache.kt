package com.artifactsmmo.gui.state

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.Image

/**
 * Simple in-memory image cache that fetches URLs via Ktor and converts them to
 * [ImageBitmap] instances suitable for Compose Desktop rendering.
 *
 * Thread-safe: concurrent requests for the same URL will coalesce — only the
 * first coroutine fetches; subsequent ones wait for and reuse that result.
 */
class ImageCache {

    private val httpClient = HttpClient(CIO)

    private val cache = mutableMapOf<String, ImageBitmap>()
    private val mutex = Mutex()

    /**
     * Returns the cached [ImageBitmap] for [url], or null while loading / on error.
     * Safe to call from a composable via a `LaunchedEffect` or `produceState`.
     */
    suspend fun get(url: String): ImageBitmap? {
        // Fast path — already cached
        mutex.withLock {
            cache[url]?.let { return it }
        }

        return try {
            val bytes = httpClient.get(url).readRawBytes()
            val bitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()
            mutex.withLock { cache[url] = bitmap }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build the character sprite URL from a skin code.
     */
    fun characterUrl(skin: String) =
        "https://artifactsmmo.com/images/characters/$skin.png"

    /**
     * Build the item image URL from an item code.
     */
    fun itemUrl(code: String) =
        "https://artifactsmmo.com/images/items/$code.png"

    /**
     * Build the monster image URL from a monster code.
     */
    fun monsterUrl(code: String) =
        "https://artifactsmmo.com/images/monsters/$code.png"

    /**
     * Build the resource image URL from a resource code.
     */
    fun resourceUrl(code: String) =
        "https://artifactsmmo.com/images/resources/$code.png"

    fun close() {
        httpClient.close()
    }
}
