package codeloupe.daemon

import kotlinx.serialization.Serializable

/** The answer of `/api/<tool>`: the tool's text, or `error: …` / `busy: …` with ok=false. */
@Serializable
data class ToolOutcome(val ok: Boolean, val text: String)
