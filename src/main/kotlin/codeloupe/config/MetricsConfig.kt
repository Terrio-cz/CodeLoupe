package codeloupe.config

import codeloupe.metrics.CategoryRule
import codeloupe.metrics.ModelPrice
import codeloupe.metrics.PriceTable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `codeloupe metrics`, from `config.json` `metrics`: `transcriptDirs` (project directories of Claude Code transcripts;
 * default every directory under `~/.claude/projects`), `categories` (`[{ "category": "tests", "tool": "regex", "file": "regex",
 * "command": "regex" }]`, tried before the built-in ones), `defaultCategories` (false = only the ones listed) and `ingestTtlMs`
 * (the daemon reads new transcript lines for the desktop app at most this often, and only when a UI API call asks; default 10 000)
 * and `prices` (`{ "asOf": "2026-10-20", "currency": "USD", "models": { "<model id>": { "input": 3, "output": 15, "cacheRead": 0.3,
 * "cacheWrite5m": 3.75, "cacheWrite1h": 6 } } }`, per million tokens: models listed here replace or add to the built-in table; only
 * `input` and `output` are required, the cache prices default to a tenth, 1.25x and 2x of `input`).
 */
data class MetricsConfig(
    val transcriptDirs: List<String> = emptyList(),
    val categories: List<CategoryRule> = emptyList(),
    val defaultCategories: Boolean = true,
    val ingestTtlMs: Long = 10_000,
    val prices: PriceTable = PriceTable.DEFAULT,
) {
    companion object {
        fun parse(file: JsonObject): MetricsConfig {
            val metrics = file["metrics"] as? JsonObject ?: return MetricsConfig()
            return MetricsConfig(
                transcriptDirs = (metrics["transcriptDirs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                categories = (metrics["categories"] as? JsonArray).orEmpty().mapNotNull { rule(it as? JsonObject) },
                defaultCategories = (metrics["defaultCategories"] as? JsonPrimitive)?.content != "false",
                ingestTtlMs = (metrics["ingestTtlMs"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it >= 0 } ?: MetricsConfig().ingestTtlMs,
                prices = prices(metrics["prices"] as? JsonObject),
            )
        }

        private fun prices(json: JsonObject?): PriceTable {
            if (json == null) return PriceTable.DEFAULT
            val models = (json["models"] as? JsonObject).orEmpty().mapNotNull { (id, entry) -> price(entry as? JsonObject)?.let { id to it } }.toMap()
            fun text(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return PriceTable.DEFAULT.overriddenBy(PriceTable(text("asOf") ?: "user config", text("currency") ?: PriceTable.DEFAULT.currency, models))
        }

        private fun price(json: JsonObject?): ModelPrice? {
            fun number(key: String) = (json?.get(key) as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it >= 0 }
            val input = number("input") ?: return null
            val output = number("output") ?: return null
            val standard = ModelPrice.standard(input, output, cacheRead = number("cacheRead") ?: input * 0.1)
            return standard.copy(cacheWrite5m = number("cacheWrite5m") ?: standard.cacheWrite5m, cacheWrite1h = number("cacheWrite1h") ?: standard.cacheWrite1h)
        }

        private fun rule(json: JsonObject?): CategoryRule? {
            fun text(key: String) = (json?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val category = text("category")?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { CategoryRule(category, tool = text("tool"), file = text("file"), command = text("command")) }.getOrNull()
        }
    }
}
