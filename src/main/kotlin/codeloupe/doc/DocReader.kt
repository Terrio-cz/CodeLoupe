package codeloupe.doc

import codeloupe.tracker.Times

/**
 * Answers a read of a [Doc] for a reader (a session key) and remembers what was served: a digest or outline of the
 * structure, the text of named sections, or all of it. A reader who already has the version gets one line; one who has
 * an older version gets what changed. Without a session key nothing is remembered.
 */
class DocReader(private val memory: DocMemory, private val clock: () -> Long = System::currentTimeMillis) {
    enum class View { DIGEST, OUTLINE, FULL }

    fun read(session: String, doc: Doc, view: View, names: List<String>, forget: Boolean = false): String {
        val previous = if (session.isEmpty() || forget) null else memory.get(session, doc.id)
        val fetched = HashMap(previous?.fetched.orEmpty())
        val known = previous?.known.orEmpty()
        var knowledge = known
        val answer = when {
            names.isNotEmpty() -> fetch(doc, names, previous, fetched)
            view == View.FULL -> full(doc, previous, fetched)
            else -> structure(doc, view, previous).also { knowledge = doc.sections.associate { it.handle to it.ownHash } }
        }
        if (session.isNotEmpty()) memory.put(session, doc.id, DocMemory.Read(clock(), if (knowledge === known) previous?.hash.orEmpty() else doc.hash, knowledge, fetched))
        return answer
    }

    /** The digest (or outline), or when the reader has seen this version of it, one line, or only what changed since. */
    private fun structure(doc: Doc, view: View, previous: DocMemory.Read?): String {
        val render = { if (view == View.OUTLINE) DocDigest.outline(doc) else DocDigest.summary(doc) }
        if (previous == null || previous.known.isEmpty()) return render()
        if (previous.hash == doc.hash) return unchanged(doc, previous)
        val now = doc.sections.associateBy { it.handle }
        val changed = doc.sections.filter { previous.known[it.handle] != it.ownHash }
        val added = changed.filter { it.handle !in previous.known }
        val removed = previous.known.keys.filter { it !in now }
        val lines = buildList {
            add("${doc.id} changed since your read at ${Times.short(previous.at)} (#${doc.hash}, ${doc.lineCount} lines):")
            changed.filter { it !in added }.forEach { add("~ ${it.handle}(${it.lines})") }
            added.forEach { add("+ ${it.handle}(${it.lines})") }
            removed.forEach { add("- $it") }
            add("fetch: section=<handle> · view=outline lists all")
        }
        return lines.joinToString("\n").take(DocDigest.LIMIT)
    }

    private fun full(doc: Doc, previous: DocMemory.Read?, fetched: MutableMap<String, String>): String {
        val fresh = doc.sections.filter { previous == null || previous.fetched[own(it)] != it.ownHash }
        doc.sections.forEach { fetched[own(it)] = it.ownHash }
        if (previous != null && fresh.isEmpty()) return unchanged(doc, previous)
        val text = cap(fresh.joinToString("\n\n") { it.own }, doc)
        if (previous == null || fresh.size == doc.sections.size) return text
        val kept = doc.sections.filter { it !in fresh }.joinToString(", ") { it.handle }
        return "${doc.id} changed since your read at ${Times.short(previous.at)}; changed sections in full, unchanged omitted ($kept):\n$text"
    }

    /** Named sections (by handle or heading prefix) or line windows (`L10-40`), each in full unless the reader already has that text. */
    private fun fetch(doc: Doc, names: List<String>, previous: DocMemory.Read?, fetched: MutableMap<String, String>): String {
        val parts = names.map { name ->
            val window = WINDOW.matchEntire(name.trim())?.let { window(doc, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            val found = window?.let { listOf(it) } ?: doc.find(name)
            if (found.isEmpty()) return@map "no section '$name'; have: ${doc.sections.take(HAVE).joinToString(", ") { it.handle }}${if (doc.sections.size > HAVE) ", … (view=outline)" else ""}"
            found.joinToString("\n\n") { section ->
                if (previous != null && previous.fetched[section.handle] == section.bodyHash) "section ${section.handle} unchanged since your read at ${Times.short(previous.at)}"
                else {
                    fetched[section.handle] = section.bodyHash
                    (if (window != null) "${doc.id} ${section.handle} of ${doc.lineCount} lines:\n" else "") + cap(section.body, doc)
                }
            }
        }
        return parts.joinToString("\n\n")
    }

    /** Lines [from]..[to] of the document, at most [MAX_WINDOW] of them. */
    private fun window(doc: Doc, from: Int, to: Int): DocSection {
        val lines = doc.text.lines()
        val start = (from - 1).coerceIn(0, lines.size)
        val end = to.coerceIn(start, minOf(lines.size, start + MAX_WINDOW))
        val text = lines.subList(start, end).joinToString("\n")
        return DocSection("L${start + 1}-$end", "lines", 1, start, text, text)
    }

    private fun unchanged(doc: Doc, previous: DocMemory.Read) =
        "${doc.id} unchanged since your read at ${Times.short(previous.at)} (#${doc.hash}, ${doc.sections.size} sections; since=none shows it again)"

    private fun cap(text: String, doc: Doc): String =
        if (text.length <= MAX_CHARS) text
        else text.take(MAX_CHARS).substringBeforeLast('\n') + "\n… cut at ${MAX_CHARS / 1000}k characters of ${text.length}; ${doc.id}: fetch a section or L<from>-<to> for the rest"

    private fun own(s: DocSection) = s.handle + "~"

    private companion object {
        val WINDOW = Regex("^[Ll](\\d+)-(\\d+)$")
        const val MAX_CHARS = 20_000
        const val MAX_WINDOW = 300
        const val HAVE = 12
    }
}
