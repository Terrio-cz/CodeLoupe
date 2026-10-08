package codeloupe.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `edit` tool, from `config.json` `write`: `mode` is `off`, `on`, or `auto` (the default: the tool is offered when
 * the gap detector finds the gaps the tool closes, see [GateRule]); `linkedWorktreesOnly` refuses writes in a repository's main
 * checkout; `deny` lists globs (relative to the repository) that are never written. A repository's own `.codeloupe.json` `write`
 * may add `linkedWorktreesOnly` and `deny` - it can only restrict, never enable.
 */
data class WriteConfig(
    val mode: String = AUTO,
    val linkedWorktreesOnly: Boolean = false,
    val deny: List<String> = emptyList(),
    val gate: GateRule = GateRule(),
) {
    /**
     * When `mode` is `auto`, the tool is offered if over the last [windowDays] days the transcripts hold at least
     * [wholeFileReads] reads of a whole code file followed by an edit of that file (coders read a file to change a declaration),
     * or [manualRenames] runs that renamed one identifier by hand across files (`rename` is missing without an IDE).
     */
    data class GateRule(val windowDays: Int = 30, val wholeFileReads: Int = 20, val manualRenames: Int = 3)

    companion object {
        const val AUTO = "auto"
        const val ON = "on"
        const val OFF = "off"

        fun parse(file: JsonObject): WriteConfig {
            val write = file["write"] as? JsonObject ?: return WriteConfig()
            fun number(source: JsonObject, key: String) = (source[key] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 }
            val gate = write["gate"] as? JsonObject
            val default = GateRule()
            return WriteConfig(
                mode = (write["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in setOf(AUTO, ON, OFF) } ?: AUTO,
                linkedWorktreesOnly = (write["linkedWorktreesOnly"] as? JsonPrimitive)?.content == "true",
                deny = strings(write["deny"]),
                gate = GateRule(
                    windowDays = gate?.let { number(it, "windowDays") }?.coerceAtLeast(1) ?: default.windowDays,
                    wholeFileReads = gate?.let { number(it, "wholeFileReads") } ?: default.wholeFileReads,
                    manualRenames = gate?.let { number(it, "manualRenames") } ?: default.manualRenames,
                ),
            )
        }

        /** The string array at `write.<key>` of a repository's `.codeloupe.json`. */
        fun strings(element: Any?): List<String> =
            (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() } }
    }
}
