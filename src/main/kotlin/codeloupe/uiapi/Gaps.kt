package codeloupe.uiapi

import kotlinx.serialization.Serializable

/**
 * Where an agent fell back to `rg`/`sed`/`cat` after a CodeLoupe call, or got no usable answer: [summary] and [items] come from
 * the transcript ingest (docs/ui-spec.md § 9.12). The weekly [report] is computed from the same data; before anything is
 * ingested it is what `codeloupe metrics gaps --out <home>/gaps-report.json` wrote, if that file exists.
 */
@Serializable
data class Gaps(val summary: List<Summary>, val items: List<Item>, val report: Report? = null) {
    /** The weekly report: how often a CodeLoupe call fell short, by week, tool and query shape. */
    @Serializable
    data class Report(val generatedAt: String?, val since: String?, val runs: Int, val calls: Int, val rows: List<Row>)

    @Serializable
    data class Row(val week: String, val tool: String, val shape: String, val kind: String, val count: Int, val examples: List<String>)

    @Serializable
    data class Summary(val tool: String, val shape: String, val fallback: String, val count: Int, val lastAt: String)

    @Serializable
    data class Item(
        val id: String,
        val at: String,
        val tool: String,
        val shape: String,
        val fallback: String,
        val reason: String,
        val session: String,
        val turn: Int?,
        val target: String,
    )
}
