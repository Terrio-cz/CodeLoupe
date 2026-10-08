package codeloupe.write

import codeloupe.lang.DeclFact
import codeloupe.lang.FileFacts
import codeloupe.lang.kotlin.KotlinAdapter

/**
 * Prototype of a lazy structural edit (CL-36): the caller sends only the members of a type that changed or are new, with a
 * `// ... existing members ...` marker wherever it leaves others out, and the parser merges them into the file by member
 * identity (kind, name, receiver, parameter types) — no apply model. A member that exists is replaced whole (its KDoc and
 * annotations included), a new one goes after the member that precedes it in the code (the end of the type after a marker, the
 * start of the body without one). The result must parse and must hold every given member exactly, else nothing is returned:
 * the rollback is that the file is never touched. Members that are not listed stay as they are; there is no deletion.
 */
class MemberMerge(private val adapter: KotlinAdapter = KotlinAdapter()) {
    sealed interface Result {
        class Merged(val text: String, val replaced: List<String>, val added: List<String>) : Result

        class Failed(val reason: String) : Result
    }

    private class Member(val fact: DeclFact, val identity: String, val text: List<String> = emptyList())

    private class Item(val member: Member?, val marker: Boolean, val line: Int)

    fun merge(path: String, source: String, type: String, code: String): Result {
        val crlf = "\r\n" in source
        val text = source.replace("\r\n", "\n")
        val facts = adapter.extract(path, text)
        if (facts.errors > 0) return Result.Failed("the file does not parse")
        val typeIndex = typeOf(facts, type) ?: return Result.Failed("no single type '$type' in the file")
        val lines = text.lines()
        val owner = facts.decls[typeIndex]
        val open = bodyOpen(lines, owner) ?: return Result.Failed("$type has no body with its own braces: add the members by hand")
        val existing = members(facts, typeIndex, open)

        val wrapped = "class __M {\n$code\n}\n"
        val given = adapter.extract("__m.kt", wrapped)
        if (given.errors > 0) return Result.Failed("the code does not parse as members of a type")
        val givenLines = wrapped.lines()
        val items = items(given, givenLines)
        val newMembers = items.mapNotNull { it.member }
        if (newMembers.isEmpty()) return Result.Failed("the code holds no member")
        newMembers.groupBy { it.identity }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { return Result.Failed("$it is given twice") }
        if (newMembers.any { it.fact.kind == "init" }) return Result.Failed("init blocks cannot be merged by identity")

        val indent = existing.firstOrNull()?.let { indentOf(lines[it.fact.declStart - 1]) } ?: (indentOf(lines[owner.declStart - 1]) + "    ")
        val replace = HashMap<Int, Pair<Int, List<String>>>()
        val insertAfter = HashMap<Int, MutableList<List<String>>>()
        val replaced = ArrayList<String>()
        val added = ArrayList<String>()
        val insertBefore = HashMap<Int, MutableList<List<String>>>()
        var anchor: Int? = null
        var before: Int? = null
        var afterMarker = false
        for ((n, item) in items.withIndex()) {
            if (item.marker) {
                afterMarker = true
                continue
            }
            val member = item.member!!
            val block = indented(givenLines.subList(member.fact.start - 1, member.fact.end), indent)
            val match = existing.firstOrNull { it.identity == member.identity }
            if (match != null) {
                replace[match.fact.start] = match.fact.end to block
                anchor = match.fact.end
                before = null
                replaced += member.identity
            } else {
                if (afterMarker) {
                    // After a marker the new member belongs before the next member the code names, else at the end of the type.
                    val next = items.drop(n + 1).firstNotNullOfOrNull { it.member?.let { m -> existing.firstOrNull { e -> e.identity == m.identity } } }
                    before = next?.fact?.start
                    anchor = if (next == null) existing.lastOrNull()?.fact?.end ?: open else null
                }
                val key = before
                if (key != null) insertBefore.getOrPut(key) { ArrayList() } += block
                else {
                    val at = anchor ?: open
                    insertAfter.getOrPut(at) { ArrayList() } += block
                    anchor = at
                }
                added += member.identity
            }
            afterMarker = false
        }

        val out = ArrayList<String>()
        var i = 1
        while (i <= lines.size) {
            insertBefore[i]?.forEach { block ->
                out += block
                out += ""
            }
            val swap = replace[i]
            val last = if (swap != null) {
                out += swap.second
                swap.first
            } else {
                out += lines[i - 1]
                i
            }
            insertAfter[last]?.forEach { block ->
                // A new member gets the blank line its neighbours have, unless it follows the opening brace.
                if (out.isNotEmpty() && out.last().isNotBlank() && last != open) out += ""
                out += block
            }
            i = last + 1
        }
        val merged = out.joinToString("\n")
        verify(path, merged, type, newMembers, indent)?.let { return Result.Failed(it) }
        return Result.Merged(if (crlf) merged.replace("\n", "\r\n") else merged, replaced, added)
    }

    /** Re-reads the result: it must parse and every given member must be in it, text for text. */
    private fun verify(path: String, merged: String, type: String, given: List<Member>, indent: String): String? {
        val facts = adapter.extract(path, merged)
        if (facts.errors > 0) return "the merge would leave the file unparsable"
        val typeIndex = typeOf(facts, type) ?: return "the type is lost after the merge"
        val lines = merged.lines()
        val open = bodyOpen(lines, facts.decls[typeIndex]) ?: return "the body is lost after the merge"
        val now = members(facts, typeIndex, open).associateBy { it.identity }
        for (member in given) {
            val got = now[member.identity] ?: return "${member.identity} is missing after the merge"
            if (dedent(lines.subList(got.fact.start - 1, got.fact.end)) != dedent(member.text)) return "${member.identity} differs after the merge"
        }
        return null
    }

    private fun items(facts: FileFacts, lines: List<String>): List<Item> {
        val wrapper = facts.decls.indexOfFirst { it.name == "__M" }
        val members = facts.decls.withIndex().filter { (_, d) -> d.parent == wrapper && !d.local }.map { (_, d) ->
            Member(d, identity(d), lines.subList(d.start - 1, d.end))
        }
        val markers = lines.withIndex().filter { (_, l) -> MARKER.matches(l) }.map { (i, _) -> Item(null, true, i + 1) }
        return (members.map { Item(it, false, it.fact.start) } + markers).sortedBy { it.line }
    }

    private fun members(facts: FileFacts, typeIndex: Int, open: Int): List<Member> =
        facts.decls.withIndex().filter { (_, d) -> d.parent == typeIndex && !d.local && d.declStart > open && d.kind != "init" && d.kind != "enum_entry" }
            .map { (_, d) -> Member(d, identity(d)) }

    private fun identity(d: DeclFact) = listOf(d.kind, d.receiver.orEmpty() + "." + d.name, d.params.joinToString(",") { it.type }).joinToString("|")
        .let { if (d.kind == "fun" || d.kind == "constructor") it else "${d.kind}|${d.name}" }

    /** The index of the one type called [type] (`Name` or `Outer.Name`) among the declarations. */
    private fun typeOf(facts: FileFacts, type: String): Int? {
        val hits = facts.decls.withIndex().filter { (_, d) ->
            d.kind in TYPES && !d.local && (d.name == type || "${d.container}.${d.name}" == type || d.container.endsWith(".${type.substringBeforeLast('.', "")}") && d.name == type.substringAfterLast('.'))
        }
        return hits.singleOrNull()?.index
    }

    /** The 1-based line of the `{` that opens the body of [owner], or null when it has no braces of its own on a line that ends there. */
    private fun bodyOpen(lines: List<String>, owner: DeclFact): Int? {
        var depth = 0
        for (n in owner.declStart..owner.end) {
            for (ch in lines[n - 1]) {
                when (ch) {
                    '(' -> depth++
                    ')' -> depth--
                    '{' -> if (depth == 0) return n.takeIf { lines[n - 1].trimEnd().endsWith("{") }
                }
            }
        }
        return null
    }

    private fun indentOf(line: String) = line.takeWhile { it == ' ' || it == '\t' }

    private fun indented(block: List<String>, indent: String): List<String> {
        val strip = block.filter { it.isNotBlank() }.minOfOrNull { indentOf(it).length } ?: 0
        return block.map { if (it.isBlank()) "" else indent + it.drop(strip) }
    }

    private fun dedent(block: List<String>): List<String> {
        val strip = block.filter { it.isNotBlank() }.minOfOrNull { indentOf(it).length } ?: 0
        return block.map { it.drop(strip).trimEnd() }
    }

    private companion object {
        val MARKER = Regex("""^\s*//\s*\.\.\..*$""")
        val TYPES = setOf("class", "interface", "enum", "object", "companion")
    }
}
