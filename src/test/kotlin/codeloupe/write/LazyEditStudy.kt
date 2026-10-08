package codeloupe.write

import codeloupe.lang.DeclFact
import codeloupe.lang.kotlin.KotlinAdapter
import java.nio.file.Path

/**
 * The comparison behind the CL-36 decision: edits that really happened in a repository's history, each as the lazy call
 * (changed and new members of one type, with markers), as search/replace chunks and as a file rewrite, in characters
 * (tokens are about a quarter of them). Only edits of one type with at least two changed or added members and at least
 * one member left alone are taken, no member removed (the lazy form has no deletion).
 */
class LazyEditStudy(private val repo: Path, private val adapter: KotlinAdapter = KotlinAdapter()) {
    class Row(
        val commit: String, val file: String, val type: String, val changed: Int, val added: Int,
        val lazy: Int, val editHunks: Int, val editWhole: Int, val rewrite: Int, val reproduced: Boolean, val note: String,
    )

    fun run(max: Int = 20, scan: Int = 600): List<Row> {
        val rows = ArrayList<Row>()
        for (sha in git("log", "--no-merges", "--format=%H", "-n", "$scan").lines().filter { it.isNotBlank() }) {
            if (rows.size >= max) break
            val files = git("diff-tree", "--no-commit-id", "--name-status", "-r", sha).lines()
                .mapNotNull { it.split('\t').takeIf { p -> p.size == 2 && p[0] == "M" && p[1].startsWith("src/main/") && p[1].endsWith(".kt") }?.get(1) }
            for (path in files) {
                row(sha, path)?.let { rows += it }
                if (rows.size >= max) break
            }
        }
        return rows
    }

    private class Member(val identity: String, val text: List<String>, val order: Int)

    private fun row(sha: String, path: String): Row? {
        val old = runCatching { git("show", "$sha~1:$path") }.getOrNull() ?: return null
        val new = runCatching { git("show", "$sha:$path") }.getOrNull() ?: return null
        val oldFacts = adapter.extract(path, old)
        val newFacts = adapter.extract(path, new)
        if (oldFacts.errors > 0 || newFacts.errors > 0) return null
        for (owner in newFacts.decls) {
            if (owner.kind !in setOf("class", "object", "interface") || owner.local) continue
            val oldOwner = oldFacts.decls.firstOrNull { it.kind == owner.kind && it.name == owner.name && it.container == owner.container && !it.local } ?: continue
            val before = members(oldFacts.decls, old, oldOwner, oldFacts)
            val after = members(newFacts.decls, new, owner, newFacts)
            if (before == null || after == null) continue
            val beforeBy = before.associateBy { it.identity }
            val afterBy = after.associateBy { it.identity }
            if (beforeBy.keys.any { it !in afterBy }) continue
            val changed = after.filter { m -> beforeBy[m.identity]?.let { dedent(it.text) != dedent(m.text) } == true }
            val added = after.filter { it.identity !in beforeBy }
            if (changed.size + added.size < 2 || changed.size + added.size >= after.size) continue
            val touched = (changed + added).sortedBy { it.order }
            val code = buildString {
                var previous = -1
                for (m in touched) {
                    if (m.order != previous + 1 && !isEmpty() || (isEmpty() && m.order != 0)) appendLine(MARKER)
                    if (isNotEmpty() && !endsWith("\n\n") && !endsWith("$MARKER\n")) appendLine()
                    appendLine(dedent(m.text).joinToString("\n"))
                    previous = m.order
                }
                if (touched.last().order != after.size - 1) appendLine(MARKER)
            }.trimEnd()
            val callOverhead = CALL + owner.name.length + path.length
            val lazy = code.length + callOverhead
            val hunks = touched.sumOf { m ->
                val was = beforeBy[m.identity]
                if (was == null) {
                    val anchor = after.getOrNull(m.order - 1)?.text?.lastOrNull().orEmpty()
                    anchor.length * 2 + dedent(m.text).joinToString("\n").length + 1 + CALL
                } else hunkChars(was.text, m.text) + CALL
            }
            val whole = touched.sumOf { m ->
                val was = beforeBy[m.identity]
                if (was == null) (after.getOrNull(m.order - 1)?.text?.lastOrNull().orEmpty().length * 2) + dedent(m.text).joinToString("\n").length + 1 + CALL
                else was.text.joinToString("\n").length + m.text.joinToString("\n").length + CALL
            }
            val reproduced = when (val r = MemberMerge(adapter).merge(path, old, owner.name, code)) {
                is MemberMerge.Result.Merged -> r.text.replace("\r\n", "\n").trimEnd() == new.replace("\r\n", "\n").trimEnd()
                is MemberMerge.Result.Failed -> false
            }
            val note = if (reproduced) "" else (MemberMerge(adapter).merge(path, old, owner.name, code) as? MemberMerge.Result.Failed)?.reason ?: "differs from the real edit"
            return Row(sha.take(7), path.substringAfterLast('/'), owner.name, changed.size, added.size, lazy, hunks, whole, new.length + path.length + CALL, reproduced, note)
            }
        return null
    }

    private fun members(decls: List<DeclFact>, text: String, owner: DeclFact, facts: codeloupe.lang.FileFacts): List<Member>? {
        val lines = text.lines()
        val index = decls.indexOf(owner)
        val direct = decls.withIndex().filter { (_, d) -> d.parent == index && !d.local && d.kind in MEMBER_KINDS && d.declStart > owner.declStart }
        if (direct.any { (_, d) -> lines.getOrNull(d.declStart - 1).isNullOrBlank() }) return null
        // Members written in the primary constructor sit on the header line: not members of the body.
        val open = (owner.declStart..owner.end).firstOrNull { lines[it - 1].trimEnd().endsWith("{") } ?: return null
        return direct.filter { (_, d) -> d.declStart > open }.mapIndexed { n, (_, d) ->
            Member(listOf(d.kind, d.name, d.params.joinToString(",") { it.type }).joinToString("|"), lines.subList(d.start - 1, d.end), n)
        }.takeIf { it.size >= 3 && facts.errors == 0 }
    }

    /** The characters a good search/replace sends for one member: the lines that differ, one line of context each side, old and new. */
    private fun hunkChars(old: List<String>, new: List<String>): Int {
        var head = 0
        while (head < old.size && head < new.size && old[head] == new[head]) head++
        var tail = 0
        while (tail < old.size - head && tail < new.size - head && old[old.size - 1 - tail] == new[new.size - 1 - tail]) tail++
        fun region(lines: List<String>) = lines.subList(maxOf(0, head - 1), minOf(lines.size, lines.size - tail + 1)).joinToString("\n")
        return region(old).length + region(new).length
    }

    private fun dedent(block: List<String>): List<String> {
        val strip = block.filter { it.isNotBlank() }.minOfOrNull { l -> l.takeWhile { it == ' ' }.length } ?: 0
        return block.map { it.drop(strip).trimEnd() }
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.toString()) + args).redirectErrorStream(false).start()
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        process.errorStream.readAllBytes()
        check(process.waitFor() == 0) { "git ${args.first()} failed" }
        return out
    }

    companion object {
        const val MARKER = "// ... existing members ..."
        private const val CALL = 60
        private val MEMBER_KINDS = setOf("fun", "property", "constructor")

        fun table(rows: List<Row>): String = buildString {
            appendLine("| commit | file | type | changed + new | lazy | search/replace (hunks) | search/replace (whole members) | file rewrite | merge reproduces the real edit |")
            appendLine("|---|---|---|---:|---:|---:|---:|---:|---|")
            rows.forEach { r ->
                appendLine("| ${r.commit} | ${r.file} | ${r.type} | ${r.changed} + ${r.added} | ${r.lazy} | ${r.editHunks} | ${r.editWhole} | ${r.rewrite} | ${if (r.reproduced) "yes" else "no: ${r.note}"} |")
            }
            fun total(f: (Row) -> Int) = rows.sumOf(f)
            appendLine("| **total** | | | ${total { it.changed }} + ${total { it.added }} | **${total { it.lazy }}** | **${total { it.editHunks }}** | **${total { it.editWhole }}** | **${total { it.rewrite }}** | ${rows.count { it.reproduced }}/${rows.size} |")
        }
    }
}
