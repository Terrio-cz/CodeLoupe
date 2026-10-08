package codeloupe.triage

import codeloupe.index.ModulePath
import codeloupe.query.DeclRow
import codeloupe.query.View
import codeloupe.query.usages.ShortSignature

/** [DeclLocator] over the index of the worktree the command ran in. */
class ViewLocator(private val view: View) : DeclLocator {
    override fun at(path: String, line: Int): DeclPointer? = pathOf(path)?.let { declAt(it, line) }

    override fun inFrame(className: String, file: String, line: Int): DeclPointer? {
        val dir = className.substringBefore('$').substringBeforeLast('.', "").replace('.', '/')
        val candidates = view.filesBySuffix("/" + if (dir.isEmpty()) file else "$dir/$file")
            .ifEmpty { view.filesBySuffix("/$file") }
        return candidates.singleOrNull()?.let { declAt(it, line) }
    }

    override fun isTest(path: String): Boolean = ModulePath.of(path).sourceSet.contains("test", ignoreCase = true)

    /** The indexed path for a path as an output printed it: exact, relative to a parent directory, or the only file with that tail. */
    private fun pathOf(raw: String): String? {
        val parts = raw.replace('\\', '/').removePrefix("file:///").split('/').filter { it.isNotEmpty() }
        for (skip in parts.indices) {
            val candidate = parts.drop(skip).joinToString("/")
            if (view.file(candidate) != null) return candidate
        }
        return view.filesBySuffix("/" + parts.last()).singleOrNull()
    }

    private fun declAt(path: String, line: Int): DeclPointer? {
        val rows = view.decls(
            "f.path = :path AND d.local = 0 AND d.start_line <= :line AND d.end_line >= :line", mapOf("path" to path, "line" to line),
            "ORDER BY (end_line - start_line) ASC LIMIT 1",
        )
        return rows.firstOrNull()?.let(::pointer)
    }

    // A name `symbol` could not parse back (a test named in backticks) is addressed by its line instead.
    private fun pointer(d: DeclRow): DeclPointer {
        val plain = IDENTIFIER.matches(d.name) && d.container.split('.').all(IDENTIFIER::matches)
        val symbol = when {
            !plain -> "${if (view.filesBySuffix("/" + d.path.substringAfterLast('/')).size == 1) d.path.substringAfterLast('/') else d.path}:${d.declLine}"
            d.container.isEmpty() -> d.name
            else -> "${d.container}.${d.name}"
        }
        return DeclPointer(d.path, d.startLine, d.endLine, ShortSignature.of(d), symbol, d.hash)
    }

    private companion object {
        val IDENTIFIER = Regex("""[\p{L}_][\p{L}\p{N}_]*""")
    }
}
