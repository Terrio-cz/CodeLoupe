package codeloupe.config

import codeloupe.metrics.CategoryRule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `codeloupe metrics`, from `config.json` `metrics`: `transcriptDirs` (project directories of Claude Code transcripts;
 * default every directory under `~/.claude/projects`), `categories` (`[{ "category": "tests", "tool": "regex", "file": "regex",
 * "command": "regex" }]`, tried before the built-in ones), `defaultCategories` (false = only the ones listed) and `ingestTtlMs`
 * (the daemon reads new transcript lines for the desktop app at most this often, and only when a UI API call asks; default 10 000).
 */
data class MetricsConfig(
    val transcriptDirs: List<String> = emptyList(),
    val categories: List<CategoryRule> = emptyList(),
    val defaultCategories: Boolean = true,
    val ingestTtlMs: Long = 10_000,
) {
    companion object {
        fun parse(file: JsonObject): MetricsConfig {
            val metrics = file["metrics"] as? JsonObject ?: return MetricsConfig()
            return MetricsConfig(
                transcriptDirs = (metrics["transcriptDirs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                categories = (metrics["categories"] as? JsonArray).orEmpty().mapNotNull { rule(it as? JsonObject) },
                defaultCategories = (metrics["defaultCategories"] as? JsonPrimitive)?.content != "false",
                ingestTtlMs = (metrics["ingestTtlMs"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it >= 0 } ?: MetricsConfig().ingestTtlMs,
            )
        }

        private fun rule(json: JsonObject?): CategoryRule? {
            fun text(key: String) = (json?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val category = text("category")?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { CategoryRule(category, tool = text("tool"), file = text("file"), command = text("command")) }.getOrNull()
        }
    }
}
