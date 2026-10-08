package codeloupe.metrics

/**
 * Prices per million tokens by model id, as they stood on [asOf]. No price is looked up at run time: the defaults below are a
 * dated copy, and `config.json` `metrics.prices` overrides or adds models (see [MetricsConfig]). A model id the table does not
 * know has no price; reports list it instead of guessing.
 */
data class PriceTable(val asOf: String, val currency: String, val models: Map<String, ModelPrice>) {
    /** The price of [modelId]: the id itself, or the id without a trailing date (`claude-haiku-4-5-20251001`); null when unknown. */
    fun priceOf(modelId: String?): ModelPrice? {
        if (modelId == null) return null
        // Claude Code's own marker for turns it wrote itself: no tokens were billed.
        if (modelId == SYNTHETIC) return ModelPrice(0.0, 0.0, 0.0, 0.0, 0.0)
        return models[modelId] ?: models[modelId.replace(DATE_SUFFIX, "")]
    }

    /** [defaults] with the models of [other] on top, stamped with the date of [other]. */
    fun overriddenBy(other: PriceTable): PriceTable = PriceTable(other.asOf, other.currency, models + other.models)

    companion object {
        private const val SYNTHETIC = "<synthetic>"
        private val DATE_SUFFIX = Regex("-\\d{8}$")

        // From the Claude API reference of 2026-10-06 (USD per million tokens). Cache reads of Opus 5.5, Sonnet 5.5 and Fable 5.1 are
        // listed there; the others follow the usual tenth of the input price, writes 1.25x and 2x of it.
        val DEFAULT = PriceTable(
            asOf = "2026-10-06",
            currency = "USD",
            models = linkedMapOf(
                "claude-fable-5-1" to ModelPrice.standard(10.0, 50.0, cacheRead = 0.25),
                "claude-fable-5" to ModelPrice.standard(10.0, 50.0, cacheRead = 0.25),
                "claude-opus-5-5" to ModelPrice.standard(4.0, 20.0, cacheRead = 0.20),
                "claude-opus-5" to ModelPrice.standard(5.0, 25.0),
                "claude-opus-4-8" to ModelPrice.standard(5.0, 25.0),
                "claude-opus-4-7" to ModelPrice.standard(5.0, 25.0),
                "claude-opus-4-6" to ModelPrice.standard(5.0, 25.0),
                "claude-sonnet-5-5" to ModelPrice.standard(2.0, 10.0, cacheRead = 0.20),
                "claude-sonnet-5" to ModelPrice.standard(2.0, 10.0),
                "claude-sonnet-4-6" to ModelPrice.standard(3.0, 15.0),
                "claude-haiku-5-5" to ModelPrice.standard(0.10, 0.50),
                "claude-haiku-4-5" to ModelPrice.standard(1.0, 5.0),
            ),
        )
    }
}
