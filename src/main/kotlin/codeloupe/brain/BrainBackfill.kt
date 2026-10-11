package codeloupe.brain

import codeloupe.repo.Registry
import java.nio.file.Path

/**
 * Gives anchors to entries written without them (the daemon was down, or the citation was a `file:line` it could not place) and follows
 * anchors whose code moved. It only asks the engine to append `@anchor` records (`brain.mjs batch "anchor ..."`), so the entry lines stay as written
 * and the brain's own writer stays the only writer. A `@sym:` is added only where the entry's text names the declaration (lines drift); the file and the
 * module are safe either way.
 */
class BrainBackfill(private val registry: Registry, private val anchors: BrainAnchors, private val engine: BrainEngine, private val env: Map<String, String>) {
    suspend fun run(root: BrainRoot, index: BrainIndex): BackfillResult {
        val codeRoot = index.info.canonicalRepo?.takeIf { it.isNotBlank() } ?: return BackfillResult(note = "workspace.json has no canonicalRepo: nothing to anchor against")
        val commands = ArrayList<String>()
        var anchored = 0
        var skipped = 0
        val entries = index.snapshot.entries.filter { it.level == "workspace" && it.id != null && !it.superseded }

        val needing = entries.filter { e -> e.anchors.isEmpty() && e.files.any(LINE::containsMatchIn) }.take(MAX_ENTRIES)
        val refs = needing.map { e -> e.files.mapNotNull { f -> LINE.find(f)?.let { LineRef(f.substring(0, it.range.first), it.groupValues[1].toInt(), e.text) } }.take(MAX_REFS_PER_ENTRY) }
        val resolved = anchors.resolve(codeRoot, refs.flatten(), trusted = false)
        var at = 0
        needing.forEachIndexed { i, e ->
            val mine = resolved.subList(at, at + refs[i].size)
            at += refs[i].size
            val found = mine.flatMap { it.anchors }.distinct().take(MAX_ANCHORS_PER_ENTRY)
            if (found.isEmpty()) skipped++ else { anchored++; found.forEach { commands += "anchor ${e.id} $it" } }
        }

        var moved = 0
        val rootPath = Path.of(registry.locate(codeRoot).worktree)
        val tracked = entries.filter { e -> e.anchors.any { it.startsWith(IndexedEntry.SYM) || it.startsWith(IndexedEntry.FILE) } }
        if (tracked.isNotEmpty()) {
            val moves = registry.query(codeRoot, speculative = false) { view ->
                tracked.flatMap { e -> e.anchors.mapNotNull { a -> (BrainAnchorCheck.check(view, a, rootPath) as? AnchorState.Moved)?.let { Triple(e.id.orEmpty(), a, it.to) } } }
            }
            moves.take(MAX_ENTRIES).forEach { (id, from, to) -> commands += "anchor $id $to --replaces $from"; moved++ }
        }
        commands.chunked(CHUNK).forEach { chunk -> engine.run(root.path, listOf("batch") + chunk.map { "$it --who codeloupe" }, env) }
        return BackfillResult(anchored, moved, skipped)
    }

    private companion object {
        val LINE = Regex(":(\\d+)(?:-\\d+)?$")
        const val MAX_ENTRIES = 150
        const val MAX_REFS_PER_ENTRY = 3
        const val MAX_ANCHORS_PER_ENTRY = 4
        const val CHUNK = 40
    }
}
