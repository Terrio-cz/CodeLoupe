package codeloupe.write

import codeloupe.index.Extraction
import codeloupe.query.DeclRow
import java.nio.file.Path

/** The new text of every file a [RenamePlan] touches, made from the files as they are on disk: any place the index got wrong is refused. */
internal class RenameEdits(private val worktree: Path, private val plan: RenamePlan) {
    /** The changes to write, and where the renamed declarations stand in the new texts (path, line of the name) for the check that follows. */
    class Result(val changes: List<FileChange>, val renamed: List<Pair<DeclRow, Pair<String, Int>>>)

    fun build(): Result {
        val paths = (plan.sites.map { it.path } + plan.family.map { it.path } + plan.importPaths.keys).distinct()
        val changes = ArrayList<FileChange>()
        val renamed = ArrayList<Pair<DeclRow, Pair<String, Int>>>()
        for (path in paths) {
            val source = SourceText.read(worktree.resolve(path)) ?: throw WriteRefused("$path is gone; the index is catching up, retry")
            val facts = Extraction.extract(path, source.text)
            val edits = ArrayList<TextEdit>()
            val declared = ArrayList<Pair<DeclRow, Int>>()
            for (row in plan.family.filter { it.path == path }) {
                val index = DeclLocator.find(facts, row) ?: throw WriteRefused("${row.fqn} is not in $path any more; read it again with symbol")
                val offset = facts.decls[index].nameOffset
                if (offset < 0) continue
                edits += rename(source.text, offset, path)
                declared += row to offset
            }
            val offsets = LineOffsets(source.text)
            for (site in plan.sites.filter { it.path == path }) {
                val offset = offsets.offset(site.line, site.col) ?: throw behind(path, site)
                if (!isName(source.text, offset)) throw behind(path, site)
                edits += rename(source.text, offset, path)
            }
            if (path.endsWith(".kt", ignoreCase = true)) edits += labels(source.text, facts, path, declared.map { it.first }, offsets)
            val added = ArrayList<String>()
            for (fqn in plan.importPaths[path].orEmpty()) {
                if (fqn in plan.sharedImports) {
                    // The import also serves declarations that keep their name: it stays, and a file that uses the renamed one imports that too.
                    if (plan.sites.any { it.path == path } || plan.family.any { it.path == path }) added += fqn.substringBeforeLast('.', "").let { owner -> if (owner.isEmpty()) plan.newName else "$owner.${plan.newName}" }
                } else {
                    edits += importEdit(source.text, fqn, path)
                }
            }
            if (added.isNotEmpty()) edits += ImportEditor(source.text, source.eol, path.endsWith(".java", ignoreCase = true), facts).add(added)
            val text = TextEdits.apply(source.text, edits.distinctBy { it.start to it.text })
            val after = Extraction.extract(plan.newPathOf(path), text)
            if (after.errors > facts.errors) throw WriteRefused("not written: $path would have ${after.errors - facts.errors} more syntax error(s)")
            val moved = plan.move?.takeIf { it.first == path }?.second
            changes += FileChange(path, source, text, moved)
            for ((row, offset) in declared) renamed += row to ((moved ?: path) to lineOf(text, shifted(edits, offset)))
        }
        return Result(changes, renamed)
    }

    // `this@f` in the body of f, `return@f` in a lambda passed to f: labels are not references of the index.
    private fun labels(text: String, facts: codeloupe.lang.FileFacts, path: String, declared: List<DeclRow>, offsets: LineOffsets): List<TextEdit> {
        val inBodies = declared.filter { it.kind == "fun" }.mapNotNull { row -> DeclLocator.find(facts, row)?.let { facts.decls[it] } }
            .flatMap { d -> KotlinLabels.within(text, d.startOffset, d.endOffset, plan.oldName, plan.newName) }
        val afterCalls = plan.sites.filter { it.path == path }.mapNotNull { offsets.offset(it.line, it.col) }
            .flatMap { KotlinLabels.afterCall(text, it, plan.oldName, plan.newName) }
        return inBodies + afterCalls
    }

    private fun rename(text: String, offset: Int, path: String): TextEdit {
        if (!isName(text, offset)) throw WriteRefused("$path: the text at offset $offset is not ${plan.oldName}; the index is behind the file, retry")
        return TextEdit(offset, offset + plan.oldName.length, plan.newName)
    }

    private fun behind(path: String, site: RenameSite) = WriteRefused("$path:${site.line}:${site.col} is not ${plan.oldName} any more; the index is behind the file, retry")

    private fun isName(text: String, offset: Int): Boolean {
        val end = offset + plan.oldName.length
        if (offset < 0 || end > text.length || text.substring(offset, end) != plan.oldName) return false
        return (offset == 0 || !isPart(text[offset - 1])) && (end == text.length || !isPart(text[end]))
    }

    private fun isPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    // `import a.b.Old`, `import static a.B.old`, `import a.b.Old as X`: the last segment of the path is the name.
    private fun importEdit(text: String, fqn: String, path: String): TextEdit {
        val pattern = Regex("""(?m)^[ \t]*import[ \t]+(?:static[ \t]+)?(${Regex.escape(fqn)})(?=[ \t]*(;|[ \t]as[ \t]|\r?$))""")
        val match = pattern.find(text) ?: throw WriteRefused("$path: the import of $fqn is not where the index says; retry")
        val group = match.groups[1]!!
        val start = group.range.last + 1 - plan.oldName.length
        return rename(text, start, path)
    }

    // Offsets before [offset] that earlier edits of the same text moved.
    private fun shifted(edits: List<TextEdit>, offset: Int): Int =
        offset + edits.distinctBy { it.start }.filter { it.start < offset }.sumOf { it.text.length - (it.end - it.start) }

    private fun lineOf(text: String, offset: Int): Int = text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1
}
