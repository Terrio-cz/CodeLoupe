package codeloupe.hooks

import kotlinx.serialization.json.JsonObject

/**
 * A shell, PowerShell or Read call found in a transcript, with the directory and session it ran in. [lines] is how long the
 * result was: for a read of a source file, the size of what was read.
 */
data class ReplayCall(val tool: String, val input: JsonObject, val cwd: String, val session: String, val id: String, val lines: Int? = null)
