package codeloupe.brain

import kotlinx.serialization.Serializable

/**
 * Where findings pile up: a score per requested file and per module, each with the entries that made it. [max] is the highest file score,
 * the number a pipeline choice looks at; a module score is the density of its whole module and is reported beside it, never folded in.
 */
@Serializable
data class RiskResult(
    val source: String = "daemon",
    val asOf: String,
    val halfLifeDays: Double,
    val max: Double,
    val level: String,
    val files: List<FileRisk>,
    val modules: List<ModuleRisk>,
    val notes: List<String> = emptyList(),
) {
    /** [score] is the sum of the weights of [entries] and [more] others; [level] is `none`, `low`, `medium` or `high`. */
    @Serializable
    data class FileRisk(val path: String, val module: String, val score: Double, val level: String, val entries: List<Contribution>, val more: Int = 0)

    @Serializable
    data class ModuleRisk(val module: String, val score: Double, val level: String, val files: List<String>, val entries: List<Contribution>, val more: Int = 0)

    /** One entry that counts: [ref] is its id (facts) or `tasks/<id>.md:<line>`, [via] what tied it (`file`, `symbol`, `module`), [weight] severity times recency. */
    @Serializable
    data class Contribution(val ref: String, val id: String? = null, val task: String? = null, val severity: String, val weight: Double, val ageDays: Int, val via: List<String>, val text: String)
}
