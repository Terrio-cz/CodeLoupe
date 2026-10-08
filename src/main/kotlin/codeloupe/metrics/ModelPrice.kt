package codeloupe.metrics

/** What one million tokens of a model cost, by price class, in the table's currency. */
data class ModelPrice(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite5m: Double,
    val cacheWrite1h: Double,
) {
    /** The cost of [usage] at this price. */
    fun cost(usage: Usage): Double =
        (usage.input * input + usage.output * output + usage.cacheRead * cacheRead + usage.cw5m * cacheWrite5m + usage.cw1h * cacheWrite1h) / PER

    companion object {
        private const val PER = 1_000_000.0

        /** The usual relations to the input price: reads a tenth, writes a quarter and a whole more (5 min and 1 h). */
        fun standard(input: Double, output: Double, cacheRead: Double = input * 0.1) =
            ModelPrice(input, output, cacheRead, cacheWrite5m = input * 1.25, cacheWrite1h = input * 2.0)
    }
}
