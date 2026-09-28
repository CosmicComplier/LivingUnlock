package com.windowslockpin.companion.core.model

import java.util.concurrent.CopyOnWriteArrayList

object SafeLogger {
    fun interface LogSink {
        fun log(level: Level, tag: String, message: String)
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val sinks = CopyOnWriteArrayList<LogSink>()

    // Redaction patterns
    private val PAIRING_TOKEN_PATTERN = Regex("""(?i)(pair_token|token|secret|dek|key)=([a-zA-Z0-9_-]+)""")
    private val RAW_QR_PATTERN = Regex("""wslp://pair\?[^\s]+""")
    private val HEX_LONG_PATTERN = Regex("""\b([0-9a-fA-F]{16,64})\b""")

    init {
        // Default sink: standard console
        sinks.add(LogSink { level, tag, message ->
            val timestamp = System.currentTimeMillis()
            println("[$timestamp] [$level] [$tag] $message")
        })
    }

    fun addSink(sink: LogSink) {
        sinks.add(sink)
    }

    fun removeSink(sink: LogSink) {
        sinks.remove(sink)
    }

    fun clearSinks() {
        sinks.clear()
    }

    fun sanitize(message: String): String {
        var clean = message
        // 1. Redact QR URLs containing parameters
        clean = RAW_QR_PATTERN.replace(clean, "wslp://pair?[REDACTED_QR_PAYLOAD]")
        // 2. Redact key-value secrets
        clean = PAIRING_TOKEN_PATTERN.replace(clean) { mr ->
            "${mr.groupValues[1]}=[REDACTED]"
        }
        // 3. Mask full hex identifiers (e.g. pcId 32 chars) to first 4 and last 4
        clean = HEX_LONG_PATTERN.replace(clean) { mr ->
            val full = mr.groupValues[1]
            if (full.length >= 16) {
                "${full.take(4)}...${full.takeLast(4)}"
            } else {
                full
            }
        }
        return clean
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        // Exception messages can contain provider input, aliases, paths, or raw protocol data.
        val extra = if (throwable != null) " - ${throwable.javaClass.simpleName}" else ""
        log(Level.ERROR, tag, message + extra)
    }

    private fun log(level: Level, tag: String, message: String) {
        val sanitized = sanitize(message)
        for (sink in sinks) {
            sink.log(level, tag, sanitized)
        }
    }
}
