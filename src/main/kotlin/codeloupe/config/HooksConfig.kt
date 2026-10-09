package codeloupe.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The plugin's hooks, from `config.json` `hooks`: `enabled: false` turns every hook off at once; `steer` is the
 * `PreToolUse` hook that points shell searches and whole-file reads at CodeLoupe ([SteerConfig]); `sessionStart` is the
 * `SessionStart` hook that adds the repository map and the state of the worktree ([SessionStartConfig]). Read again on every
 * hook call, so a change needs no daemon restart. `weight` is the `UserPromptSubmit`/`Stop` hook that tells the user when a session
 * carries too much ([WeightConfig]).
 */
data class HooksConfig(
    val enabled: Boolean = true,
    val steer: SteerConfig = SteerConfig(),
    val sessionStart: SessionStartConfig = SessionStartConfig(),
    val weight: WeightConfig = WeightConfig(),
) {
    /**
     * One advisory line the first time the context of a session reaches each of the [warnAt] sizes (tokens), naming the [top]
     * tool results that weigh most. It never blocks; after `/compact` the sizes count again.
     */
    data class WeightConfig(val enabled: Boolean = true, val warnAt: List<Int> = listOf(150_000, 300_000), val top: Int = 3)

    /**
     * The context a session starts with: the worktree's state - branch, task and, when [changes] is on, the changed declarations
     * (at most [changesLimit] lines) - and, when [map] is on, the ranked map of the repository (a fresh session only), all within
     * [budget] tokens. The map is off: in 20 paired sessions it cost about 7 % more and displaced no reads or searches (docs/plan.md, CL-148).
     */
    data class SessionStartConfig(val enabled: Boolean = true, val map: Boolean = false, val budget: Int = 1_200, val changes: Boolean = true, val changesLimit: Int = 12)

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
            val start = section["sessionStart"] as? JsonObject
            val default = SteerConfig()
            val startDefault = SessionStartConfig()
            val weight = section["weight"] as? JsonObject
            val weightDefault = WeightConfig()
            return HooksConfig(
                enabled = (section["enabled"] as? JsonPrimitive)?.content != "false",
                steer = SteerConfig(
                    mode = (steer?.get("mode") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in setOf(ADVISE, REDIRECT, OFF) } ?: default.mode,
                    minLines = number(steer, "minLines")?.coerceAtLeast(1) ?: default.minLines,
                    maxPerSession = number(steer, "maxPerSession") ?: default.maxPerSession,
                    giveUpAfter = number(steer, "giveUpAfter")?.coerceAtLeast(1) ?: default.giveUpAfter,
                ),
                sessionStart = SessionStartConfig(
                    enabled = (start?.get("enabled") as? JsonPrimitive)?.content != "false",
                    map = (start?.get("map") as? JsonPrimitive)?.content == "true",
                    budget = number(start, "budget")?.coerceIn(100, 8_000) ?: startDefault.budget,
                    changes = (start?.get("changes") as? JsonPrimitive)?.content != "false",
                    changesLimit = number(start, "changesLimit")?.coerceIn(1, 100) ?: startDefault.changesLimit,
                ),
                weight = WeightConfig(
                    enabled = (weight?.get("enabled") as? JsonPrimitive)?.content != "false",
                    warnAt = (weight?.get("warnAt") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { n -> n > 0 } }?.sorted()?.distinct()?.takeIf { it.isNotEmpty() } ?: weightDefault.warnAt,
                    top = number(weight, "top")?.coerceIn(1, 10) ?: weightDefault.top,
                ),
            )
        }

        private fun number(source: JsonObject?, key: String): Int? = (source?.get(key) as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 }
    }
}
