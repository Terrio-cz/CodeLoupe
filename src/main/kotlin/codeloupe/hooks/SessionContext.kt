package codeloupe.hooks

import codeloupe.changes.ChangesQuery
import codeloupe.config.HooksConfig.SessionStartConfig
import codeloupe.query.RepoMap
import codeloupe.repo.Registry
import codeloupe.taskcode.TaskPattern
import codeloupe.taskcode.WorktreeBranches
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

/**
 * What a session starts with in a repository the daemon has indexed: where it is (branch, task, what the worktree changed) and
 * the ranked map of the repository (when the settings ask for it), together within the configured token budget. A resumed or
 * compacted session gets the state of the worktree alone: it has seen the map. Null for a directory that is no git repository, a repository the daemon has not
 * indexed, a timeout or any failure; nothing here starts a build.
 */
class SessionContext(private val registry: Registry, private val projects: () -> Collection<String>, private val timeoutMs: Long = TIMEOUT_MS) {
    suspend fun build(cwd: String, source: String?, settings: SessionStartConfig): String? {
        val location = runCatching { registry.locate(cwd) }.getOrNull() ?: return null
        val repo = registry.known(location.commonDir) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            val withMap = settings.map && source != "resume" && source != "compact"
            val branch = branch(location.commonDir, location.worktree)
            val task = branch?.let { TaskPattern.of(registry.mainWorktree(location.commonDir), projects()).idsIn(it).firstOrNull() }
            val changed = if (settings.changes) changes(location.worktree, settings.changesLimit) else null
            val state = state(Path.of(location.worktree).fileName.toString(), branch, repo.defaultRef, task, changed?.text)
            val budgetChars = chars(settings.budget)
            val trimmed = if (withMap) cut(state, budgetChars * STATE_SHARE / 100) else cut(state, budgetChars)
            if (!withMap) return@withTimeoutOrNull trimmed
            val room = settings.budget - tokens(trimmed) - tokens(HINT) - FOOTER_TOKENS
            val map = if (room < MIN_MAP_TOKENS) null else registry.query(location.worktree) { RepoMap.run(it, RepoMap.Args(focus = changed?.files.orEmpty(), budget = room)) }
            listOfNotNull(trimmed, map, HINT).joinToString("\n")
        }
    }

    private class Changed(val text: String, val files: List<String>)

    private suspend fun changes(worktree: String, limit: Int): Changed? = runCatching {
        registry.changes(worktree) { set, after, before ->
            // The callers under each declaration are what a long session asks `changes` for; a start only needs to know what moved.
            val lines = ChangesQuery.run(set, after, before, ChangesQuery.Args(limit = limit)).lines().filterNot { it.startsWith("      ") }
            Changed(lines.take(limit + 1 + EXTRA_LINES).joinToString("\n"), set.files.map { it.path }.take(MAX_FOCUS))
        }
    }.getOrNull()

    private fun branch(commonDir: String, worktree: String): String? {
        val here = worktree.replace('\\', '/').trimEnd('/')
        return runCatching { WorktreeBranches.of(commonDir).entries.firstOrNull { it.value.trimEnd('/').equals(here, ignoreCase = true) }?.key }.getOrNull()
    }

    private fun state(name: String, branch: String?, defaultRef: String, task: String?, changes: String?): String = buildString {
        append("CodeLoupe orientation for $name: ")
        append(if (branch == null) "no branch checked out" else "branch $branch")
        if (task != null) append(" (task $task)")
        append(", default branch $defaultRef.")
        if (changes != null) append('\n').append(changes)
    }

    private fun cut(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit).substringBeforeLast('\n').ifEmpty { text.take(limit) } + "\n…"

    private fun chars(tokens: Int) = (tokens * CHARS_PER_TOKEN).toInt()

    private fun tokens(text: String) = Math.ceil(text.length / CHARS_PER_TOKEN).toInt()

    companion object {
        /** The project's token estimate for code text, as [RepoMap] uses it. */
        const val CHARS_PER_TOKEN = 3.2
        private const val TIMEOUT_MS = 6_000L
        private const val STATE_SHARE = 40
        private const val MIN_MAP_TOKENS = 100

        // The map's own closing line ("… +n more files") comes on top of the budget it is given.
        private const val FOOTER_TOKENS = 24
        private const val EXTRA_LINES = 6
        private const val MAX_FOCUS = 8
        private const val HINT = "Orient with outline, find, symbol and usages (CodeLoupe) instead of ls, find and reading files."
    }
}
