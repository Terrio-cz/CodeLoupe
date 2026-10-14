package codeloupe.hooks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The JSON Claude Code hands a hook on stdin, with the fields CodeLoupe reads. */
class HookInput(private val json: JsonObject) {
    val event: String get() = text("hook_event_name").orEmpty()
    val tool: String get() = text("tool_name").orEmpty()
    val toolInput: JsonObject get() = json["tool_input"] as? JsonObject ?: JsonObject(emptyMap())
    val cwd: String? get() = text("cwd")?.takeIf { it.isNotEmpty() }
    val session: String get() = text("session_id").orEmpty()
    val toolUseId: String? get() = text("tool_use_id")?.takeIf { it.isNotEmpty() }
    val transcriptPath: String? get() = text("transcript_path")?.takeIf { it.isNotEmpty() }
    val source: String? get() = text("source")?.takeIf { it.isNotEmpty() }
    val login: SessionLogin
        get() = SessionLogin(
            text("codeloupe_login")?.takeIf { UUID.matches(it) },
            text("codeloupe_account")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 },
            (json["codeloupe_token_login"] as? JsonPrimitive)?.content == "true",
        )

    private fun text(key: String): String? = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        val UUID = Regex("[0-9A-Fa-f-]{8,64}")
    }
}
