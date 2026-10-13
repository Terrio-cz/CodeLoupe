package codeloupe.brain

import codeloupe.index.ModulePath
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.pow
import kotlin.math.round

/**
 * Findings density per file and module (CL-235): what the brain holds about the code, weighed by severity and recency. A finding counts when it is
 * a `!P<n>` line of a task, a finding of the workspace facts (`!P<n>` or a fixed `x`) or one of its gotchas; facts and decisions say what is
 * true, not where code broke. A superseded entry, or one a landed check covers, counts for nothing. The weight is the severity's weight times
 * `0.5^(age / half-life)`, an undated entry counting as one half-life old. A file's score sums the entries tied to it (a file or symbol anchor, or
 * a file or symbol the text names); a module's sums the entries tied to the module, however they name it.
 */
class BrainRisk(private val index: BrainIndex) {
    private class Counted(val item: IndexedEntry, val severity: String, val weight: Double, val ageDays: Int)

    fun score(request: RiskRequest, code: CodeContext, now: Instant): RiskResult {
        val counted = counted(request, now)
        val limit = request.limit.coerceIn(1, MAX_LIMIT)
        val facts = code.files.associateBy { it.path }
        val scored = request.files.distinct().map { requested -> fileRisk(requested, facts, code, counted, request, limit) }
        val files = scored.map { it.risk }
        val modules = scored.filter { it.risk.module.isNotEmpty() }.groupBy { it.risk.module }.map { (module, inModule) -> moduleRisk(module, inModule, counted, request, limit) }
        val max = files.maxOfOrNull { it.score } ?: 0.0
        return RiskResult(
            asOf = now.toString(), halfLifeDays = request.halfLifeDays, max = max, level = level(max, request),
            files = files.sortedByDescending { it.score }, modules = modules.sortedByDescending { it.score }, notes = code.notes,
        )
    }

    private class FileScore(val risk: RiskResult.FileRisk, val tied: List<Counted>)

    private fun fileRisk(requested: String, facts: Map<String, CodeFileFacts>, code: CodeContext, counted: List<Counted>, request: RiskRequest, limit: Int): FileScore {
        val path = code.paths.firstOrNull { it.equals(requested, ignoreCase = true) || it.endsWith("/$requested", ignoreCase = true) } ?: requested.replace('\\', '/').removePrefix("./")
        val module = (facts[path]?.module?.takeIf { it.isNotEmpty() } ?: ModulePath.of(path).module).lowercase()
        val keys = code.symbolKeysByPath[path].orEmpty()
        val tied = counted.mapNotNull { c ->
            val via = buildList {
                if (c.item.pathKeys.any { it.matches(path) }) add("file")
                if (c.item.symbolAnchors.any { it in keys } || c.item.namedSymbols.any { it in keys }) add("symbol")
            }
            if (via.isEmpty()) null else c to via
        }
        val sorted = tied.sortedByDescending { it.first.weight }
        val score = tied.sumOf { it.first.weight }
        return FileScore(RiskResult.FileRisk(path, module, round2(score), level(score, request), sorted.take(limit).map { (c, via) -> contribution(c, via) }, (sorted.size - limit).coerceAtLeast(0)), tied.map { it.first })
    }

    // A module's score counts what names the module and what is tied to one of its requested files, each entry once.
    private fun moduleRisk(module: String, files: List<FileScore>, counted: List<Counted>, request: RiskRequest, limit: Int): RiskResult.ModuleRisk {
        val viaFile = files.flatMap { it.tied }.toSet()
        val tied = counted.filter { it in viaFile || module in it.item.moduleAnchors || module in it.item.moduleHints }.sortedByDescending { it.weight }
        val score = tied.sumOf { it.weight }
        return RiskResult.ModuleRisk(module, round2(score), level(score, request), files.map { it.risk.path }, tied.take(limit).map { contribution(it, listOf("module")) }, (tied.size - limit).coerceAtLeast(0))
    }

    private fun counted(request: RiskRequest, now: Instant): List<Counted> {
        val weights = WEIGHTS + request.weights
        return index.items.mapNotNull { item ->
            val e = item.entry
            if (e.superseded) return@mapNotNull null
            val severity = severity(e) ?: return@mapNotNull null
            val at = instantOf(e.at)
            if (at != null && at.isAfter(now)) return@mapNotNull null
            val ageDays = if (at == null) request.halfLifeDays else (now.epochSecond - at.epochSecond) / SECONDS_PER_DAY
            val weight = (weights[severity] ?: 0.0) * 0.5.pow(ageDays / request.halfLifeDays.coerceAtLeast(1.0))
            if (weight <= 0.0) null else Counted(item, severity, weight, ageDays.toInt())
        }
    }

    private fun contribution(c: Counted, via: List<String>) = RiskResult.Contribution(c.item.entry.id ?: c.item.entry.where, c.item.entry.id, c.item.entry.task, c.severity, round2(c.weight), c.ageDays, via, c.item.entry.text)

    private fun level(score: Double, request: RiskRequest) = when {
        score <= 0.0 -> "none"
        score >= request.high -> "high"
        score >= request.medium -> "medium"
        else -> "low"
    }

    companion object {
        const val HALF_LIFE_DAYS = 60.0
        const val MEDIUM = 4.0
        const val HIGH = 12.0
        val WEIGHTS = mapOf("P0" to 10.0, "P1" to 6.0, "P2" to 3.0, "P3" to 1.0, "x" to 1.0, "gotcha" to 1.0)
        private const val MAX_LIMIT = 100
        private const val SECONDS_PER_DAY = 86_400.0
        private val SEVERITY = Regex("^!P(\\d)")

        /** `P0`..`P3`, `x` or `gotcha` for an entry that says where code broke; null for the rest (facts, decisions, evidence, blockers of a task). */
        fun severity(e: BrainEntry): String? {
            val p = SEVERITY.find(e.text.trimStart())?.groupValues?.get(1)?.let { "P$it" }
            return when {
                e.level == "machine" -> null
                e.isTaskLine -> p
                e.kind == "finding" -> p ?: "x"
                e.kind == "gotcha" -> "gotcha"
                else -> null
            }
        }

        /** An entry's `at` (`2026-10-06T10:00`, `2026-10-06`, or an ISO instant) as UTC; null when it has none or cannot be read. */
        fun instantOf(text: String?): Instant? {
            val t = text?.trim().orEmpty().ifEmpty { return null }
            return runCatching { Instant.parse(t) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(t).toInstant(ZoneOffset.UTC) }.getOrNull()
                ?: runCatching { LocalDate.parse(t).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
        }

        private fun round2(x: Double) = round(x * 100) / 100
    }
}
