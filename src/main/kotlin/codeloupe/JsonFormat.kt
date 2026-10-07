package codeloupe

import kotlinx.serialization.json.Json

/** The JSON dialect of every file and message CodeLoupe writes: defaults and nulls written out. */
object JsonFormat {
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}
