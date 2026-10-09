package codeloupe.hooks

import codeloupe.platform.PathCase
import codeloupe.repo.Registry
import java.nio.file.Files
import java.nio.file.Path

/** [SourceFiles] over the daemon's indexes: only repositories it has already indexed, and only what that base holds. Failures answer "no". */
class IndexedSources(private val registry: Registry, private val ignoreCase: Boolean = PathCase.insensitive) : SourceFiles {
    private class Place(val worktree: String, val relative: String, val base: Path)

    override fun file(path: String): SourceFile? = runCatching {
        val real = Path.of(path).toRealPath().takeIf { Files.isRegularFile(it) } ?: return null
        val place = place(real.parent, real.toString()) ?: return null
        if (!IndexProbe.hasFile(place.base, place.relative)) return null
        SourceFile(real.toString().replace('\\', '/'), place.relative, lines(real))
    }.getOrNull()

    override fun hasSources(dir: String): Boolean = runCatching {
        val real = Path.of(dir).toRealPath().takeIf { Files.isDirectory(it) } ?: return false
        val place = place(real, real.toString()) ?: return false
        IndexProbe.hasSourcesUnder(place.base, place.relative)
    }.getOrDefault(false)

    private fun place(directory: Path, full: String): Place? {
        val location = registry.locate(directory.toString())
        val state = registry.known(location.commonDir) ?: return null
        val base = synchronized(state) { state.baseFile } ?: return null
        val text = full.replace('\\', '/')
        val root = location.worktree.trimEnd('/')
        if (!text.startsWith("$root/", ignoreCase = ignoreCase) && !text.equals(root, ignoreCase = ignoreCase)) return null
        return Place(root, text.substring(minOf(text.length, root.length + 1)), base)
    }

    private fun lines(file: Path): Int {
        val bytes = Files.readAllBytes(file)
        val newlines = bytes.count { it == NEWLINE }
        return if (bytes.isEmpty() || bytes.last() == NEWLINE) newlines else newlines + 1
    }

    private companion object {
        const val NEWLINE: Byte = 10
    }
}
