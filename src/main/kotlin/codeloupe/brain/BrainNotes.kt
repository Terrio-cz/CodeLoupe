package codeloupe.brain

import codeloupe.query.DeclRow
import codeloupe.query.Resolver
import codeloupe.repo.Registry

/**
 * One line per symbol for `symbol` and `context`: how many brain entries are anchored on it and the best few, by id, so the reader knows what the brain
 * says before changing it. Only from an index already built (a first call builds it in the background); a note must never make the tool wait.
 */
class BrainNotes(private val brain: BrainService, private val registry: Registry) {
    suspend fun lines(root: String, name: String): List<String> {
        val brains = brain.roots()
        if (brains.isEmpty()) return emptyList()
        val repo = mainWorktree(root) ?: return emptyList()
        val indexes = brains.mapNotNull { r -> brain.cached(r) ?: run { brain.warm(r); null } }
            .filter { it.info.canonicalRepo?.let(::normalize) == repo }
        if (indexes.isEmpty()) return emptyList()
        val decls = registry.query(root) { view -> Resolver.resolve(view, name).filter { !it.local } }
        // A name that matches several declarations is answered with a list to choose from, not a symbol to annotate.
        return decls.singleOrNull()?.let { decl -> listOfNotNull(line(decl, indexes)) }.orEmpty()
    }

    private fun mainWorktree(root: String): String? = runCatching { normalize(registry.mainWorktree(registry.locate(root).commonDir).toString()) }.getOrNull()

    private fun line(decl: DeclRow, indexes: List<BrainIndex>): String? {
        val keys = BrainCodeContext.keys(decl).toSet()
        val entries = indexes.flatMap { index -> index.items.filter { !it.entry.isTaskLine && !it.entry.superseded && (it.symbolAnchors.any(keys::contains) || it.namedSymbols.any(keys::contains)) } }
            .map { it.entry }.distinctBy { it.id ?: it.where }
            .sortedWith(compareBy<BrainEntry> { RANK[it.kind] ?: RANK.size }.thenByDescending { it.at.orEmpty() })
        if (entries.isEmpty()) return null
        val shown = entries.take(MAX_SHOWN).joinToString(" · ") { e -> "${e.id ?: e.where}${e.task?.let { " $it" }.orEmpty()} ${e.kind}: ${brief(e.text)}" }
        val more = entries.size - MAX_SHOWN
        val label = if (decl.container.isEmpty()) decl.name else "${decl.container}.${decl.name}"
        return "brain: ${entries.size} on $label — $shown${if (more > 0) " · +$more more" else ""} (brain_search for the whole entries)"
    }

    private fun brief(text: String): String {
        val plain = text.replace(Regex("^(?:!P\\d|[!+~?=>x])\\s*"), "").replace(Regex("\\s+"), " ").trim()
        return if (plain.length <= MAX_TEXT) plain else plain.take(MAX_TEXT).trimEnd() + "…"
    }

    private fun normalize(path: String) = path.replace('\\', '/').trimEnd('/').lowercase()

    private companion object {
        val RANK = mapOf("decision" to 0, "finding" to 1, "gotcha" to 2, "fact" to 3)
        const val MAX_SHOWN = 2
        const val MAX_TEXT = 90
    }
}
