package codeloupe.overlay

import codeloupe.git.WorktreeGit
import codeloupe.index.FilePut
import codeloupe.index.InlineParse
import codeloupe.index.Store
import codeloupe.index.StoreUpdate
import codeloupe.lang.Languages
import codeloupe.platform.Sha1
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Works out how a worktree differs from a base. A reconcile asks git (first check, new base, mass change); the
 * check on every later query walks the worktree and reads only the files whose stamps moved.
 */
internal object OverlayPlanner {
    /** More moved files than this (a checkout, a rebase) are settled faster by git, which knows its own index. */
    private const val INCREMENTAL_MAX = 200
    private const val BOM = "\uFEFF"

    /**
     * [previous] is the base [state] was last checked against, when it still exists: a file that matched that base and
     * has not been touched since takes its facts from there instead of being parsed again.
     */
    fun reconcile(state: OverlayState, baseCommit: String, baseFile: Path, previous: Path? = null): OverlayChange {
        val worktree = state.worktree
        val (differing, prune, scan) = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val changed = CompletableFuture.supplyAsync({ WorktreeGit.changedSince(worktree, baseCommit) }, executor)
            val untracked = CompletableFuture.supplyAsync({ WorktreeGit.untracked(worktree) }, executor)
            val prune = WorktreeGit.ignoredDirs(worktree)
            val scan = WorktreeScan.scan(Path.of(worktree), prune)
            Triple((changed.get() + untracked.get()).filterTo(HashSet()) { Languages.languageOf(it) != null }, prune, scan)
        }
        val basePaths = BaseFiles(baseFile).use { it.paths() }
        val target = HashMap<String, Stamp>()
        val missing = ArrayList<String>()
        for (path in differing) {
            val stamp = scan[path]
            if (stamp != null) target[path] = stamp else if (path in basePaths) missing += path
        }
        // Outside a sparse checkout's cone a file is absent but not deleted: the base answers for it.
        val sparse = WorktreeGit.skipWorktree(worktree, missing)
        for (path in missing) if (path !in sparse) target[path] = Stamp.MISSING
        // Seen by the walk, neither tracked nor reported untracked: git ignores them.
        val ignored = scan.keys.filterTo(HashSet()) { it !in differing && it !in basePaths }
        val unchanged = if (previous == null) emptySet() else target.keys.filterTo(HashSet()) { it !in state.entries && state.scan[it] == target[it] }
        return change(state, target, scan, prune, ignored, baseCommit, previous, unchanged)
    }

    /** Null when no stamp moved since the last check. [state] must be relative to [baseCommit]. */
    fun incremental(state: OverlayState, baseCommit: String, baseFile: Path): OverlayChange? {
        val scan = WorktreeScan.scan(Path.of(state.worktree), state.prune)
        val moved = (scan.keys + state.scan.keys).filter { scan[it] != state.scan[it] }
        if (moved.isEmpty()) return null
        if (moved.size > INCREMENTAL_MAX) return reconcile(state, baseCommit, baseFile)
        val target = HashMap(state.entries)
        val ignored = HashSet(state.ignored)
        val remembered = HashMap(scan)
        val unknown = ArrayList<String>()
        val gone = ArrayList<String>()
        BaseFiles(baseFile).use { base ->
            for (path in moved) {
                val stamp = scan[path]
                if (stamp == null) {
                    ignored -= path
                    if (base.has(path)) gone += path else target -= path
                    continue
                }
                if (path in ignored) continue
                if (!base.has(path)) {
                    if (path in state.entries) target[path] = stamp else unknown += path
                    continue
                }
                // Too large to read into the daemon's heap: compared by hash as a stream, a build worker parses it.
                if (stamp.size > InlineParse.MAX_FILE_BYTES) {
                    when (Sha1.ofFile(Path.of(state.worktree, path))) {
                        null -> state.scan[path]?.let { remembered[path] = it } ?: remembered.remove(path)
                        base.hash(path) -> target -= path
                        else -> target[path] = stamp
                    }
                    continue
                }
                val baseText = base.content(path)!!
                val text = read(Path.of(state.worktree, path))
                when {
                    // Locked or gone while we looked: keep the old stamp, so the next check reads it again.
                    text == null -> state.scan[path]?.let { remembered[path] = it } ?: remembered.remove(path)
                    sameText(text, baseText) -> target -= path
                    else -> target[path] = stamp
                }
            }
        }
        // A narrowed sparse checkout removes files from disk without deleting them: the base answers for those.
        val sparse = WorktreeGit.skipWorktree(state.worktree, gone)
        for (path in gone) if (path in sparse) target -= path else target[path] = Stamp.MISSING
        var prune = state.prune
        if (unknown.isNotEmpty()) {
            val gitIgnores = WorktreeGit.ignored(state.worktree, unknown)
            for (path in unknown) if (path in gitIgnores) ignored += path else target[path] = scan.getValue(path)
            // Typically a module's first build output: leave the whole directory out of later walks.
            if (gitIgnores.isNotEmpty()) prune = WorktreeGit.ignoredDirs(state.worktree)
        }
        return change(state, target, remembered, prune, ignored, baseCommit)
    }

    private fun change(
        state: OverlayState,
        target: Map<String, Stamp>,
        scan: Map<String, Stamp>,
        prune: Set<String>,
        ignored: Set<String>,
        baseCommit: String,
        copySource: Path? = null,
        copyable: Set<String> = emptySet(),
    ): OverlayChange {
        val puts = ArrayList<FilePut>()
        val copies = ArrayList<FilePut>()
        val tombstones = ArrayList<String>()
        for ((path, stamp) in target) {
            if (state.entries[path] == stamp) continue
            if (stamp == Stamp.MISSING) {
                tombstones += path
                continue
            }
            val put = FilePut(path, file = Path.of(state.worktree, path).toString(), size = stamp.size, mtime = stamp.mtime)
            if (path in copyable) copies += put else puts += put
        }
        val removes = state.entries.keys.filter { it !in target }
        val meta = mapOf("base" to baseCommit, "worktree" to state.worktree, "format" to Store.FORMAT)
        val update = StoreUpdate(puts, removes, tombstones, copies, copySource?.toString(), meta)
        return OverlayChange(update, target, scan, prune, ignored)
    }

    // A file that differs from its base copy only in line ends or a BOM has the same facts.
    private fun sameText(a: String, b: String) = normalize(a) == normalize(b)

    private fun normalize(text: String) = text.removePrefix(BOM).replace("\r\n", "\n")

    private fun read(file: Path): String? = try {
        Files.readAllBytes(file).toString(Charsets.UTF_8)
    } catch (_: IOException) {
        null
    }
}
