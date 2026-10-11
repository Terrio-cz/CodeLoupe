package codeloupe.brain

import codeloupe.index.ModulePath
import codeloupe.query.DeclRow
import codeloupe.query.Resolver
import codeloupe.query.View
import codeloupe.query.usages.UsageFinder
import codeloupe.repo.Registry
import codeloupe.taskcode.CallerFiles
import codeloupe.taskcode.TaskPattern
import codeloupe.tracker.Trackers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Reads the code a question names from the daemon's index: for each file its declarations (as the names anchors use), its module, the
 * files that call it, and the tasks whose commits changed it (the task-to-code links of the history). Bounded in files and in time; whatever
 * cannot be read becomes a note, never an error, so ranking falls back to text.
 */
class BrainCodeContext(private val registry: Registry, private val trackers: Trackers, private val timeoutMs: Long = 6_000) {
    suspend fun of(codeRoot: String?, files: List<String>): CodeContext {
        if (files.isEmpty()) return CodeContext.NONE
        val root = codeRoot?.takeIf { it.isNotBlank() } ?: return CodeContext.unavailable("no code root: workspace.json has no canonicalRepo and none was given")
        return try {
            withTimeout(timeoutMs) { build(root, files.distinct().take(MAX_FILES)) }
        } catch (e: TimeoutCancellationException) {
            CodeContext.unavailable("the code index of $root did not answer in ${timeoutMs / 1000} s; ranked by text")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CodeContext.unavailable("code index of $root not readable (${e.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(120)}); ranked by text")
        }
    }

    private class Read(val path: String, val module: String, val decls: Int, val keys: Set<String>, val callers: Map<String, Int>)

    private suspend fun build(root: String, files: List<String>): CodeContext {
        val location = registry.locate(root)
        val prefix = location.worktree.replace('\\', '/').trimEnd('/') + "/"
        val reads = registry.query(root, speculative = false) { view ->
            val finder = lazy { UsageFinder(view) }
            files.mapIndexedNotNull { i, raw -> read(view, finder, relative(raw, prefix), withCallers = i < MAX_CALLER_FILES) }
        }
        val notes = ArrayList<String>()
        val missing = files.size - reads.size
        if (missing > 0) notes += "$missing of ${files.size} file(s) are not in the code index of $root"
        val tasks = tasksTouching(root, reads.map { it.path }, notes)
        val facts = reads.map { CodeFileFacts(it.path, it.module, it.decls, it.callers.size, tasks.byPath[it.path].orEmpty().toList().sorted()) }
        return CodeContext(
            files = facts,
            symbolKeys = reads.flatMapTo(HashSet()) { it.keys },
            callerPaths = reads.flatMapTo(HashSet()) { it.callers.keys },
            taskIds = tasks.all,
            notes = notes,
            root = root,
        )
    }

    private fun relative(raw: String, prefix: String): String {
        val path = raw.replace('\\', '/').removePrefix("./")
        return if (path.startsWith(prefix, ignoreCase = true)) path.substring(prefix.length) else path
    }

    private fun read(view: View, finder: Lazy<UsageFinder>, wanted: String, withCallers: Boolean): Read? {
        val path = Resolver.resolvePath(view, wanted) ?: return null
        val decls = view.decls("f.path = :path AND d.local = 0", mapOf("path" to path))
        val callers = if (withCallers) CallerFiles.targets(view, path).takeIf { it.isNotEmpty() }?.let { CallerFiles.of(finder.value, it, path) }.orEmpty() else emptyMap()
        val module = decls.firstOrNull()?.module?.takeIf { it.isNotEmpty() } ?: ModulePath.of(path).module
        return Read(path, module, decls.size, decls.flatMapTo(HashSet()) { keys(it) }, callers)
    }

    private class Tasks(val all: Set<String>, val byPath: Map<String, Set<String>>)

    private suspend fun tasksTouching(root: String, paths: List<String>, notes: MutableList<String>): Tasks {
        if (paths.isEmpty()) return Tasks(emptySet(), emptyMap())
        return try {
            val location = registry.locate(root)
            val repo = registry.repo(location.commonDir)
            val pattern = TaskPattern.of(registry.mainWorktree(repo.commonDir), trackers.projects())
            val store = registry.taskCodes.current(repo, pattern)
            val byPath = paths.associateWith { path -> store.commitsTouching(path).take(MAX_COMMITS).flatMap { it.second }.toSet() }
            Tasks(byPath.values.flatten().toSet(), byPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notes += "task history not readable (${e.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(100)}): no task-to-code links"
            Tasks(emptySet(), emptyMap())
        }
    }

    companion object {
        const val MAX_FILES = 24
        const val MAX_CALLER_FILES = 6
        const val MAX_COMMITS = 80
        private val TYPE_KINDS = setOf("class", "interface", "object", "enum", "annotation", "typealias")

        /** Every dotted suffix of the name with two or more parts, and the bare name of a type, lowercase: the forms an anchor or a finding uses. */
        fun keys(decl: DeclRow): List<String> {
            val parts = decl.fqn.split('.')
            val suffixes = (2..parts.size).map { n -> parts.takeLast(n).joinToString(".").lowercase() }
            return if (decl.kind in TYPE_KINDS) suffixes + decl.name.lowercase() else suffixes
        }
    }
}
