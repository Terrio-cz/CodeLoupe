package codeloupe.processes

import codeloupe.events.Scrubber
import codeloupe.workspace.OrphanDirs
import codeloupe.workspace.Workspace
import codeloupe.workspace.WorkspaceList
import java.io.File
import java.nio.file.Path

/**
 * Says which workspace a process belongs to: the one whose directory holds the process's working directory (the deepest
 * one, since worktrees may sit inside the main checkout), else the one whose path appears in its command line. Pure over the
 * process list and the registry; nothing is read from the OS here except to normalise paths.
 */
object ProcessAttribution {
    private class Place(val repo: String, val workspace: Workspace, val key: String)

    fun attribute(processes: List<ProcessInfo>, registry: WorkspaceList, self: Long = ProcessHandle.current().pid()): List<ProcessEntry> {
        val places = registry.repos.flatMap { repo -> repo.workspaces.map { Place(repo.name, it, key(it.path)) } }.sortedByDescending { it.key.length }
        if (places.isEmpty()) return emptyList()
        return processes.filter { it.pid != self }.mapNotNull { p ->
            val byCwd = p.cwd?.let { cwd -> key(cwd).let { k -> places.firstOrNull { inside(k, it.key) } } }
            val place = byCwd ?: p.commandLine?.let { line -> normalise(line).let { text -> places.firstOrNull { mentions(text, it.key) } } } ?: return@mapNotNull null
            entry(p, place, if (byCwd != null) "cwd" else "command line")
        }.sortedWith(compareBy({ it.repo.lowercase() }, { it.workspace.lowercase() }, { -it.rssMb }, { it.pid }))
    }

    /** Whether [process] belongs to the directory [path] by the same rule. */
    fun belongsTo(process: ProcessInfo, path: String): Boolean {
        val target = key(path)
        return process.cwd?.let { inside(key(it), target) } == true || process.commandLine?.let { mentions(normalise(it), target) } == true
    }

    private fun entry(p: ProcessInfo, place: Place, via: String) = ProcessEntry(
        pid = p.pid, startMs = p.startMs, kind = p.kind, name = p.name, commandLine = display(p.commandLine.orEmpty()), cwd = p.cwd?.replace('\\', '/'),
        rssMb = (p.rssBytes ?: 0) / MB, repo = place.repo, workspace = place.workspace.name, workspaceState = place.workspace.state, path = place.workspace.path, via = via,
    )

    private fun display(commandLine: String): String {
        val masked = Scrubber.text(commandLine.take(MAX_LINE))
        return if (masked.length <= SHOWN) masked else masked.take(SHOWN - 1) + "…"
    }

    private fun inside(path: String, directory: String) = path == directory || path.startsWith("$directory/")

    // The path must end where a path ends in a command line: the end, a separator, a quote or a space.
    private fun mentions(text: String, directory: String): Boolean {
        var from = text.indexOf(directory)
        while (from >= 0) {
            val end = from + directory.length
            if (end == text.length || text[end] in BOUNDARY) return true
            from = text.indexOf(directory, from + 1)
        }
        return false
    }

    private fun key(path: String): String = OrphanDirs.key(runCatching { Path.of(path) }.getOrElse { Path.of(".") }).trimEnd('/')

    private fun normalise(text: String): String = text.replace('\\', '/').let { if (File.separatorChar == '\\') it.lowercase() else it }

    private const val MB = 1024L * 1024
    private const val MAX_LINE = 4_000
    private const val SHOWN = 300
    private const val BOUNDARY = "/\"' ;,"
}
