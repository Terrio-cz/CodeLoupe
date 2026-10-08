package codeloupe.write

import codeloupe.query.DeclRow
import codeloupe.query.View
import codeloupe.query.usages.Label
import codeloupe.query.usages.Usage
import codeloupe.query.usages.UsageFinder
import java.nio.file.Path

/**
 * The check that stands in for a compiler: the files as they would be after the rename are indexed beside the rest, and by the same
 * rules the usages are found by, every place that is renamed must resolve to the renamed declaration (none captured by another
 * declaration of the new name, none without its declaration), and every place that used another declaration of the old name must
 * still do (an import or a lookup the rename has changed under it).
 */
internal object RenameCheck {
    /** Null when the renamed worktree would resolve as planned, else the first place that would not. */
    fun verify(view: View, worktree: Path, plan: RenamePlan, built: RenameEdits.Result): String? {
        val texts = built.changes.associate { it.target to it.text }
        val removed = built.changes.mapNotNull { change -> change.movedTo?.let { change.path } }.toSet()
        return OverlayProbe.with(view, worktree, texts, removed) { probe ->
            val family = built.renamed.map { (row, where) -> locate(probe, row, plan.newName, where) ?: return@with "${row.fqn} is not declared as ${plan.newName} after the rename" }
            val finder = UsageFinder(probe)
            val planned = plan.sites.groupBy({ plan.newPathOf(it.path) }, { it.line }).mapValues { (_, lines) -> lines.distinct().size }
            val after = lines(finder.usages(family), plan.newName) { usage -> finder.denotesOnly(usage.ref, family) }
            planned.entries.firstOrNull { (path, count) -> (after[path] ?: 0) < count }?.let { (path, count) ->
                return@with "after the rename $path would use ${plan.newName} in ${after[path] ?: 0} of $count planned place(s): another declaration of that name is in the way"
            }
            val others = plan.others.mapNotNull { other -> same(probe, other, plan.newPathOf(other.path)) }
            if (others.size == plan.others.size && others.isNotEmpty()) {
                val now = lines(finder.usages(others), plan.oldName) { usage -> finder.denotesOnly(usage.ref, others) }
                plan.othersExact.entries.firstOrNull { (path, count) -> (now[plan.newPathOf(path)] ?: 0) < count }?.let { (path, count) ->
                    return@with "after the rename $path would no longer resolve ${now[plan.newPathOf(path)] ?: 0} of $count use(s) of another ${plan.oldName} (an import or a lookup the rename changed)"
                }
            }
            null
        }
    }

    // Lines per file of the uses of [name] that are exact, or are unsure but can only mean the declarations asked about.
    private fun lines(usages: List<Usage>, name: String, only: (Usage) -> Boolean): Map<String, Int> =
        usages.filter { it.ref.name == name && (it.label == Label.EXACT || (it.label == Label.CANDIDATE && only(it))) }.groupBy { it.ref.path }.mapValues { (_, hits) -> hits.map { it.ref.line }.distinct().size }

    private fun locate(probe: View, row: DeclRow, newName: String, where: Pair<String, Int>): DeclRow? =
        probe.decls(
            "f.path = :p AND d.name = :n AND d.kind = :k AND d.start_line <= :l AND d.end_line >= :l",
            mapOf("p" to where.first, "n" to newName, "k" to row.kind, "l" to where.second),
        ).minByOrNull { it.endLine - it.startLine }

    // A declaration that kept its name, in the probe: the same file, kind and qualified name.
    private fun same(probe: View, row: DeclRow, path: String): DeclRow? =
        probe.decls("d.fqn = :fqn AND d.kind = :k AND f.path = :p AND d.param_count = :c", mapOf("fqn" to row.fqn, "k" to row.kind, "p" to path, "c" to row.paramCount))
            .minByOrNull { kotlin.math.abs(it.declLine - row.declLine) }
}
