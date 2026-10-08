package codeloupe.processes

import codeloupe.events.Scrubber
import codeloupe.workspace.OrphanDirs
import codeloupe.workspace.Workspace
import codeloupe.workspace.WorkspaceList
import java.io.File
import java.nio.file.Path

/**
 * Says which workspace a process belongs to: the one whose directory holds the process's working directory (the deepest
 * one, since worktrees may sit inside the main checkout), else, for a Gradle daemon, the one it last built in, else the one whose
 * path appears in its command line. Pure over the
 * process list and the registry; nothing is read from the OS here except to normalise paths.
 */
object ProcessAttribution {
    // [raw] is the path as the registry names it: a command line carries paths as they were typed, not resolved.
    private class Place(val repo: String, val workspace: Workspace, val key: String, val raw: String)

    fun attribute(processes: List<ProcessInfo>, registry: WorkspaceList, self: Long = ProcessHandle.current().pid()): List<ProcessEntry> {
        val places = registry.repos.flatMap { repo -> repo.workspaces.map { Place(repo.name, it, key(it.path), normalise(it.path).trimEnd('/')) } }.sortedByDescending { it.key.length }
        if (places.isEmpty()) return emptyList()
        return processes.filter { it.pid != self }.mapNotNull { p ->
            val byCwd = p.cwd?.let { cwd -> key(cwd).let { k -> places.firstOrNull { inside(k, it.key) } } }
            val byBuild = if (byCwd == null) p.buildDir?.let { dir -> key(dir).let { k -> places.firstOrNull { inside(k, it.key) } } } else null
            val place = byCwd ?: byBuild ?: p.commandLine?.let { line -> normalise(line).let { text -> places.firstOrNull { mentions(text, it.key) || mentions(text, it.raw) } } } ?: return@mapNotNull null
            entry(p, place, if (byCwd != null) "cwd" else if (byBuild != null) "last build" else "command line")
        }.sortedWith(compareBy({ it.repo.lowercase() }, { it.workspace.lowercase() }, { -it.rssMb }, { it.pid }))
    }

    /** Whether [process] belongs to the directory [path] by the same rule. */
    fun belongsTo(process: ProcessInfo, path: String): Boolean {
        val target = key(path)
        val raw = normalise(path).trimEnd('/')
        return process.cwd?.let { inside(key(it), target) } == true || process.buildDir?.let { inside(key(it), target) } == true ||
            process.commandLine?.let { line -> normalise(line).let { mentions(it, target) || mentions(it, raw) } } == true
    }

    private fun entry(p: ProcessInfo, place: Place, via: String) = ProcessEntry(
        pid = p.pid, startMs = p.startMs, kind = p.kind, name = p.name, commandLine = display(p.commandLine.orEmpty()), cwd = p.cwd?.replace('\\', '/'),
        rssMb = (p.rssBytes ?: 0) / MB, repo = place.repo, workspace = place.workspace.name, workspaceState = place.workspace.state, path = place.workspace.path, via = via,
        busy = p.busy,
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

    private fun key(path: String): String = OrphanDirs.key(existing(runCatching { Path.of(path) }.getOrElse { Path.of(".") })).trimEnd('/')

    // A path that does not exist (a build directory since deleted) is resolved from its nearest directory that does, so a short
    // Windows name or a symbolic link in the front of it reads the same as in the workspace's own path.
    private fun existing(path: Path): Path {
        var base: Path? = path.toAbsolutePath().normalize()
        val rest = ArrayDeque<String>()
        while (base != null && !java.nio.file.Files.exists(base)) {
            base.fileName?.let { rest.addFirst(it.toString()) }
            base = base.parent
        }
        val real = base?.let { runCatching { it.toRealPath() }.getOrNull() } ?: base ?: path
        return rest.fold(real) { acc, name -> acc.resolve(name) }
    }

    private fun normalise(text: String): String = text.replace('\\', '/').let { if (File.separatorChar == '\\') it.lowercase() else it }

    private const val MB = 1024L * 1024
    private const val MAX_LINE = 4_000
    private const val SHOWN = 300
    private const val BOUNDARY = "/\"' ;,"
}
