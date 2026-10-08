package codeloupe.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The plugin's hooks, from `config.json` `hooks`: `enabled: false` turns every hook off at once; `steer` is the
 * `PreToolUse` hook that points shell searches and whole-file reads at CodeLoupe ([SteerConfig]). Read again on every hook
 * call, so a change needs no daemon restart.
 */
data class HooksConfig(
    val enabled: Boolean = true,
    val steer: SteerConfig = SteerConfig(),
) {
    /**
     * `mode` is `advise` (the command runs and the model is told the equivalent CodeLoupe call), `redirect` (the first
     * time, the command is refused with the equivalent call; the same command again runs) or `off`. A source file counts as
     * large from [minLines] lines. At most [maxPerSession] pieces of advice go to one session, and none after [giveUpAfter] in a
     * row without a CodeLoupe call in between on that repository (an agent that cannot or will not use the tools is not nagged).
     */
    data class SteerConfig(val mode: String = ADVISE, val minLines: Int = 150, val maxPerSession: Int = 40, val giveUpAfter: Int = 4) {
        val active: Boolean get() = mode != OFF
    }

    companion object {
        const val ADVISE = "advise"
        const val REDIRECT = "redirect"
        const val OFF = "off"

        fun parse(file: JsonObject): HooksConfig {
            val hooks = file["hooks"]
            if (hooks is JsonPrimitive) return HooksConfig(enabled = hooks.content != "false")
            val section = hooks as? JsonObject ?: return HooksConfig()
            val steer = section["steer"] as? JsonObject
            val default = SteerConfig()
            return HooksConfig(
                enabled = (section["enabled"] as? JsonPrimitive)?.content != "false",
                steer = SteerConfig(
                    mode = (steer?.get("mode") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in setOf(ADVISE, REDIRECT, OFF) } ?: default.mode,
                    minLines = number(steer, "minLines")?.coerceAtLeast(1) ?: default.minLines,
                    maxPerSession = number(steer, "maxPerSession") ?: default.maxPerSession,
                    giveUpAfter = number(steer, "giveUpAfter")?.coerceAtLeast(1) ?: default.giveUpAfter,
                ),
            )
        }

        private fun number(source: JsonObject?, key: String): Int? = (source?.get(key) as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 }
    }
}
