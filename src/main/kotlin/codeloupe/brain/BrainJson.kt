package codeloupe.brain

import kotlinx.serialization.json.Json

/** The JSON a tool answers an agent with: empty lists, false flags and nulls left out, because every token of it is read. */
object BrainJson {
    val compact = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }
}
