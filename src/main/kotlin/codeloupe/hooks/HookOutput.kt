package codeloupe.hooks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The JSON a hook prints for Claude Code. */
object HookOutput {
    /** The model gets [text] next to the tool result; the tool runs as it would have, permissions untouched. */
    fun context(event: String, text: String): JsonObject = buildJsonObject {
        put("hookSpecificOutput", buildJsonObject {
            put("hookEventName", event)
            put("additionalContext", text)
        })
    }

    /** A line shown to the user; the model does not get it and nothing is blocked. */
    fun notice(text: String): JsonObject = buildJsonObject { put("systemMessage", text) }

    /** The tool call is refused and the model reads [reason]. */
    fun deny(reason: String): JsonObject = buildJsonObject {
        put("hookSpecificOutput", buildJsonObject {
            put("hookEventName", "PreToolUse")
            put("permissionDecision", "deny")
            put("permissionDecisionReason", reason)
        })
    }
}
