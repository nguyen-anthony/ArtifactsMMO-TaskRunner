package com.artifactsmmo.app.task

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Thread-safe logger that writes to a file and keeps recent entries in a ring buffer.
 * Replaces stdout println calls from background task runners so they don't pollute the UI.
 */
class TaskLogger(
    logDir: String = "logs",
    private val maxMemoryEntries: Int = 500
) {
    data class LogEntry(
        val timestamp: LocalDateTime,
        val characterName: String?,
        val message: String
    ) {
        private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
        fun formatted(): String {
            val ts = timestamp.format(fmt)
            val prefix = if (characterName != null) "[$ts] [$characterName]" else "[$ts]"
            return "$prefix $message"
        }

        fun fileLine(): String {
            val ts = timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val prefix = if (characterName != null) "$ts [$characterName]" else ts
            return "$prefix $message"
        }
    }

    private val entries = ConcurrentLinkedDeque<LogEntry>()
    private val logFile: File

    init {
        val dir = File(logDir)
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, "tasks.log")
    }

    fun log(characterName: String?, message: String) {
        val entry = LogEntry(LocalDateTime.now(), characterName, message)

        // Add to ring buffer
        entries.addLast(entry)
        while (entries.size > maxMemoryEntries) {
            entries.pollFirst()
        }

        // Append to file
        try {
            logFile.appendText(entry.fileLine() + "\n")
        } catch (_: Exception) {
            // Silently ignore file write failures
        }
    }

    fun log(message: String) = log(null, message)

    /**
     * Get recent log entries, optionally filtered by character name.
     */
    fun getRecent(count: Int = 50, characterName: String? = null): List<LogEntry> {
        val filtered = if (characterName != null) {
            entries.filter { it.characterName.equals(characterName, ignoreCase = true) }
        } else {
            entries.toList()
        }
        return filtered.takeLast(count)
    }
}
