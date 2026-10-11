package codeloupe.brain

import codeloupe.index.ModulePath
import codeloupe.query.DeclRow
import codeloupe.query.Resolver
import codeloupe.query.View
import codeloupe.repo.Registry
import java.nio.file.Files
import java.nio.file.Path

/** Turns the `file:line` a finding cites into anchors of the enclosing declaration, file and module. */
class BrainAnchors(private val registry: Registry) {
    suspend fun resolve(codeRoot: String, refs: List<LineRef>, trusted: Boolean): List<ResolvedRef> {
        if (refs.isEmpty()) return emptyList()
        val root = Path.of(registry.locate(codeRoot).worktree)
        return registry.query(codeRoot, speculative = false) { view -> refs.map { one(view, root, it, trusted) } }
    }

    private fun one(view: View, root: Path, ref: LineRef, trusted: Boolean): ResolvedRef {
        val path = pathOf(view, root, ref.file) ?: return ResolvedRef(ref.file, ref.line, emptyList())
        val decl = DeclAnchors.enclosing(view, path, ref.line)?.takeIf { trusted || names(ref.text, it) }
        val module = decl?.module?.takeIf { it.isNotEmpty() } ?: ModulePath.of(path).module
        val anchors = listOfNotNull(
            decl?.let { DeclAnchors.sym(view, it) },
            "${IndexedEntry.FILE}$path",
            module.takeIf { it.isNotEmpty() }?.let { "${IndexedEntry.MOD}$it" },
        )
        return ResolvedRef(ref.file, ref.line, anchors)
    }

    /** The indexed path of a cited file: exact, by its tail (`a/.../File.kt` elides directories), or, for a file the index does not hold, on disk. */
    private fun pathOf(view: View, root: Path, cited: String): String? {
        val raw = cited.replace('\\', '/').removePrefix("./")
        if ("..." in raw) {
            val head = raw.substringBefore("...").trimEnd('/')
            val tail = raw.substringAfterLast("...").trimStart('/')
            return view.filesBySuffix("/$tail").filter { head.isEmpty() || it.startsWith("$head/") }.distinct().singleOrNull()
        }
        return Resolver.resolvePath(view, raw) ?: raw.takeIf { runCatching { Files.isRegularFile(root.resolve(it)) }.getOrDefault(false) }
    }

    companion object {
        /** Does the text name the declaration (or the type it is in)? Lines drift, a name does not. */
        fun names(text: String?, decl: DeclRow): Boolean {
            if (text == null) return false
            val container = decl.container.substringAfterLast('.')
            return listOf(decl.name, container).filter { it.length >= MIN_NAME }.any { Regex("(?<![A-Za-z0-9_])${Regex.escape(it)}(?![A-Za-z0-9_])").containsMatchIn(text) }
        }

        private const val MIN_NAME = 4
    }
}
