package codeloupe

import kotlinx.serialization.json.Json

/** The JSON dialect of every file and message CodeLoupe writes: defaults written, nulls left out. */
object JsonFormat {
    val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }
}
