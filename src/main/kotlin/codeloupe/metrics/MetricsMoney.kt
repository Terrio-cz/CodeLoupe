package codeloupe.metrics

/** Money from the token counts of a report's runs: what they cost per role and model, and what they would cost on another model. */
object MetricsMoney {
    fun byRole(runs: List<RunSummary>, prices: PriceTable): Map<String, RoleMoney> =
        runs.groupBy { it.role }.mapValues { (_, rs) ->
            val unknown = rs.filter { prices.priceOf(it.model) == null }
            RoleMoney(rs.size, rs.sumOf { r -> prices.priceOf(r.model)?.cost(r.usage) ?: 0.0 }, unknown.size, unknown.map { it.model ?: "(no model)" }.toSet())
        }

    /** Cost of the priced runs of [role] with their own token counts at [price]: an upper bound on a saving, since another model takes other turns. */
    fun asIf(runs: List<RunSummary>, prices: PriceTable, role: String, price: ModelPrice): Double =
        runs.filter { it.role == role && prices.priceOf(it.model) != null }.sumOf { price.cost(it.usage) }

    /** The cost of the priced runs of [role] on the models they ran on. */
    fun actual(runs: List<RunSummary>, prices: PriceTable, role: String): Double =
        runs.filter { it.role == role }.sumOf { r -> prices.priceOf(r.model)?.cost(r.usage) ?: 0.0 }

    /** Per model: runs and cost, of all roles together. */
    fun byModel(runs: List<RunSummary>, prices: PriceTable): Map<String, Pair<Int, Double?>> =
        runs.groupBy { it.model ?: "(no model)" }.mapValues { (model, rs) ->
            val price = prices.priceOf(model.takeIf { it != "(no model)" })
            rs.size to price?.let { p -> rs.sumOf { p.cost(it.usage) } }
        }
}
