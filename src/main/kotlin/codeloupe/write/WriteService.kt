package codeloupe.write

import codeloupe.index.Extraction
import codeloupe.lang.DeclFact
import codeloupe.lang.FileFacts
import codeloupe.lang.Languages
import codeloupe.query.DeclRow
import codeloupe.query.Format
import codeloupe.query.Resolver
import codeloupe.repo.Registry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * The writes of the `edit` tool: a declaration found by name in the index is looked up again in the file as it is on disk (the index
 * may be a moment behind), must carry the hash the caller read, is edited as text, and the result is read again before it is
 * written. Nothing reaches the disk unless the file still parses as well as before and still declares what it declared.
 */
class WriteService(private val registry: Registry, private val policy: WritePolicy, journal: WriteJournal) {
    private val applier = WriteApplier(policy, journal)

    /** A declaration of the worktree, found in the file as it is now. */
    private class Target(val worktree: Path, val main: Path, val row: DeclRow, val source: SourceText, val facts: FileFacts, val index: Int) {
        val decl: DeclFact get() = facts.decls[index]
    }

    suspend fun replace(root: String, name: String, hash: String?, code: String): String =
        change(root, "replace", name, hash) { target -> editor(target).replace(target.index, code) }

    suspend fun insertAfter(root: String, name: String, hash: String?, code: String): String =
        change(root, "insert_after", name, hash) { target -> editor(target).insertAfter(target.index, code) }

    suspend fun insertBefore(root: String, name: String, hash: String?, code: String): String =
        change(root, "insert_before", name, hash) { target -> editor(target).insertBefore(target.index, code) }

    suspend fun delete(root: String, name: String, hash: String?): String =
        change(root, "delete", name, hash) { target -> editor(target).delete(target.index) }

    suspend fun insertMember(root: String, type: String, hash: String?, code: String, position: String?): String {
        if (position != null && position !in POSITIONS) throw WriteRefused("position is one of ${POSITIONS.joinToString()}")
        return change(root, "insert_member", type, hash, TYPES) { target -> editor(target).insertMember(target.index, code, position ?: "end") }
    }

    suspend fun addImports(root: String, file: String, imports: List<String>): String {
        if (imports.isEmpty()) throw WriteRefused("pass imports: the names to import (a.b.C)")
        val location = registry.locate(root)
        val worktree = Path.of(location.worktree)
        val relative = file.replace('\\', '/').removePrefix("./")
        return withContext(Dispatchers.IO) {
            // Where the file is comes first: nothing outside the worktree is opened, not even to say whether it exists.
            policy.refusal(worktree, registry.mainWorktree(location.commonDir), relative, null)?.let { throw WriteRefused(it) }
            val source = SourceText.read(worktree.resolve(relative)) ?: throw WriteRefused("no file $relative in the worktree")
            policy.refusal(worktree, registry.mainWorktree(location.commonDir), relative, source.text)?.let { throw WriteRefused(it) }
            val before = Extraction.extract(relative, source.text)
            val edits = ImportEditor(source.text, source.eol, relative.endsWith(".java", ignoreCase = true), before).add(imports)
            if (edits.isEmpty()) return@withContext "unchanged: $relative has them all"
            val text = TextEdits.apply(source.text, edits)
            val after = Extraction.extract(relative, text)
            EditVerifier.verifyDeclarationsUnchanged(before, after)?.let { throw WriteRefused("not written: $it") }
            val written = applier.apply("add_imports", root, worktree, registry.mainWorktree(location.commonDir), listOf(FileChange(relative, source, text)), imports.joinToString())
            registry.touched(root)
            "added ${edits.sumOf { it.text.count { c -> c == '\n' } }.coerceAtLeast(1)} import line(s) to $relative  (${written.single().after?.take(HASH)})"
        }
    }

    suspend fun createFile(root: String, path: String, code: String): String {
        val location = registry.locate(root)
        val worktree = Path.of(location.worktree)
        val relative = path.replace('\\', '/').removePrefix("./")
        return withContext(Dispatchers.IO) {
            if (Languages.languageOf(relative) == null) throw WriteRefused("$relative is not a Kotlin or Java file")
            policy.refusal(worktree, registry.mainWorktree(location.commonDir), relative, null)?.let { throw WriteRefused(it) }
            val target = worktree.resolve(relative)
            if (Files.exists(target)) throw WriteRefused("$relative exists: use replace_symbol or insert_member on its declarations")
            val text = NewSource.text(relative, code, siblingEol(target.parent), Extraction.extract(relative, code))
            val facts = Extraction.extract(relative, text)
            if (facts.errors > 0) throw WriteRefused("not written: the file would have ${facts.errors} syntax error(s)")
            val written = applier.apply("create_file", root, worktree, registry.mainWorktree(location.commonDir), listOf(FileChange(relative, null, text)), relative)
            registry.touched(root)
            "created $relative  ${text.count { it == '\n' }} lines, ${facts.decls.count { !it.local && it.parent < 0 }} declaration(s)  (${written.single().after?.take(HASH)})"
        }
    }

    suspend fun rename(root: String, name: String, to: String, hash: String?, dryRun: Boolean): String {
        val location = registry.locate(root)
        val worktree = Path.of(location.worktree)
        val main = registry.mainWorktree(location.commonDir)
        val plan = registry.query(root) { view -> RenamePlanner(view).plan(name, to) }
        if (dryRun) return RenameReport.text(plan, applied = false)
        if (hash.isNullOrBlank()) throw WriteRefused("pass hash: the hash= that symbol printed for $name (or dry_run=true to see the plan)")
        return withContext(Dispatchers.IO) {
            checkHash(worktree, plan, hash)
            val built = RenameEdits(worktree, plan).build()
            registry.query(root) { view -> RenameCheck.verify(view, worktree, plan, built) }?.let { throw WriteRefused("not written: $it") }
            applier.apply("rename", root, worktree, main, built.changes, "$name -> $to")
            registry.touched(root)
            RenameReport.text(plan, applied = true)
        }
    }

    // The hash is that of the named declaration: of any of the declarations that go by the name (a class and its constructors).
    private fun checkHash(worktree: Path, plan: RenamePlan, hash: String) {
        val current = plan.targets.mapNotNull { row ->
            val source = SourceText.read(worktree.resolve(row.path)) ?: return@mapNotNull null
            val facts = Extraction.extract(row.path, source.text)
            DeclLocator.find(facts, row)?.let { facts.decls[it].hash }
        }
        if (current.isEmpty()) throw WriteRefused("${plan.targets.first().fqn} is not in its file any more; read it again with symbol")
        if (hash !in current) throw WriteRefused("hash mismatch: ${plan.targets.first().fqn} changed since you read it (now hash=${current.first()}); read it again with symbol")
    }

    private fun editor(target: Target) = SymbolEditor(target.source.text, target.source.eol, target.facts)

    // The line end of the neighbours: a new file in a CRLF folder is CRLF.
    private fun siblingEol(directory: Path?): String {
        val files = directory?.takeIf { Files.isDirectory(it) }?.let { dir -> Files.list(dir).use { s -> s.filter { Languages.languageOf(it.fileName.toString()) != null }.limit(5).toList() } }.orEmpty()
        return files.firstNotNullOfOrNull { runCatching { SourceText.read(it)?.eol }.getOrNull() } ?: "\n"
    }

    private suspend fun change(root: String, op: String, name: String, hash: String?, kinds: Set<String>? = null, plan: (Target) -> Planned): String {
        val target = target(root, name, hash, kinds)
        return withContext(Dispatchers.IO) {
            val planned = plan(target)
            val edit = planned.edit ?: return@withContext "unchanged: ${target.row.fqn} is ${planned.note}"
            val text = TextEdits.apply(target.source.text, listOf(edit))
            val after = Extraction.extract(target.row.path, text)
            val range = EditVerifier.Range(edit.start, edit.end, edit.text.length, planned.mustDeclare)
            EditVerifier.verify(target.facts, after, range)?.let { throw WriteRefused("not written: $it") }
            val written = applier.apply(op, root, target.worktree, target.main, listOf(FileChange(target.row.path, target.source, text)), target.row.fqn)
            registry.touched(root)
            describe(planned, target, after, edit, written.single().after)
        }
    }

    private fun describe(planned: Planned, target: Target, after: FileFacts, edit: TextEdit, sha: String?): String {
        val head = "${planned.note} ${target.row.fqn}  ${target.row.path}"
        val inside = after.decls.firstOrNull { !it.local && it.startOffset >= edit.start && it.endOffset <= edit.start + edit.text.length }
        val moved = if (inside != null) "  ${Format.range(inside.start, inside.end)}  hash=${inside.hash}" else ""
        return "$head$moved  (file ${sha?.take(HASH)})"
    }

    private suspend fun target(root: String, name: String, hash: String?, kinds: Set<String>?): Target {
        if (hash.isNullOrBlank()) throw WriteRefused("pass hash: the hash= that symbol printed for $name")
        val location = registry.locate(root)
        val rows = registry.query(root) { view -> Resolver.resolve(view, name, kinds) }
        val row = pick(name, rows)
        val worktree = Path.of(location.worktree)
        val main = registry.mainWorktree(location.commonDir)
        return withContext(Dispatchers.IO) {
            val source = SourceText.read(worktree.resolve(row.path)) ?: throw WriteRefused("${row.path} is gone; the index is catching up, retry")
            policy.refusal(worktree, main, row.path, source.text)?.let { throw WriteRefused(it) }
            val facts = Extraction.extract(row.path, source.text)
            val index = DeclLocator.find(facts, row) ?: throw WriteRefused("${row.fqn} is not in ${row.path} any more; read it again with symbol")
            if (facts.decls[index].hash != hash) {
                throw WriteRefused("hash mismatch: ${row.fqn} changed since you read it (now hash=${facts.decls[index].hash}); read it again with symbol")
            }
            Target(worktree, main, row, source, facts, index)
        }
    }

    private fun pick(name: String, rows: List<DeclRow>): DeclRow {
        if (rows.isEmpty()) throw WriteRefused("no declaration \"$name\"")
        rows.singleOrNull()?.let { return it }
        rows.filter { it.kind != "constructor" }.singleOrNull()?.let { return it }
        throw WriteRefused("${rows.size} declarations match \"$name\" - qualify it (Type.member, member(ParamType), path/File.kt:line):\n" + rows.take(LISTED).joinToString("\n", transform = Format::head))
    }

    private companion object {
        const val HASH = 10
        const val LISTED = 10
        val POSITIONS = listOf("start", "end", "after_properties")
        val TYPES = setOf("class", "interface", "object", "enum", "companion", "annotation")
    }
}
