package codeloupe.brain

import codeloupe.query.Resolver
import codeloupe.query.View
import java.nio.file.Files
import java.nio.file.Path

/**
 * Follows anchors through renames and moves. A `@sym:` that no declaration answers to is looked up by its simple name and the simple name of
 * its container: exactly one such declaration elsewhere is where the symbol went. A `@file:` that is gone is looked up by file name: exactly
 * one file of that name is where it went. Anything else is gone, and an entry whose code is gone is a candidate to be superseded.
 */
object BrainAnchorCheck {
    /** [root] is the checkout: a file the index does not hold (a script, a document) counts as there when it exists on disk. */
    fun check(view: View, anchor: String, root: Path): AnchorState = when {
        anchor.startsWith(IndexedEntry.SYM) -> symbol(view, anchor.removePrefix(IndexedEntry.SYM))
        anchor.startsWith(IndexedEntry.FILE) -> file(view, anchor.removePrefix(IndexedEntry.FILE), root)
        else -> AnchorState.Found
    }

    private fun symbol(view: View, value: String): AnchorState {
        if (Resolver.resolve(view, value).isNotEmpty()) return AnchorState.Found
        val parts = value.substringBefore('(').split('.')
        val name = parts.last()
        val container = parts.getOrNull(parts.size - 2)
        val candidates = view.decls("d.name = :name AND d.local = 0", mapOf("name" to name))
            .filter { container == null || it.fqn.split('.').getOrNull(it.fqn.split('.').size - 2) == container }
        return candidates.singleOrNull()?.let { AnchorState.Moved(DeclAnchors.sym(view, it)) } ?: AnchorState.Gone
    }

    private fun file(view: View, path: String, root: Path): AnchorState {
        if (view.file(path) != null || runCatching { Files.isRegularFile(root.resolve(path)) }.getOrDefault(false)) return AnchorState.Found
        val name = path.substringAfterLast('/')
        val same = view.filesBySuffix("/$name").filter { it != path }
        return same.singleOrNull()?.let { AnchorState.Moved("${IndexedEntry.FILE}$it") } ?: AnchorState.Gone
    }
}
