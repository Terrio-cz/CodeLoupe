package codeloupe.secrets.imports

import codeloupe.secrets.SecretScope
import codeloupe.secrets.SecretStore
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Copies confirmed variables from the scan into the store, in this process, and optionally replaces them in their source
 * files with references. Rerunning it changes nothing: a value the store already holds is skipped. Two selected occurrences
 * of one name and scope with different values are a conflict and neither is stored; select one of them. A name the store
 * holds under another value is kept unless [overwrite] says the files win.
 */
class EnvImporter(private val store: SecretStore, private val backups: ImportBackups) {
    private class Stored(val variable: FoundVariable, val outcome: ImportResult.Outcome, val scope: SecretScope)

    private class Replaced(val ids: Set<String> = emptySet(), val files: Int = 0, val backupId: String? = null, val notReplaced: List<ImportResult.NotReplaced> = emptyList())

    fun run(scan: EnvScanner.Result, selections: List<ImportSelection>, replaceSources: Boolean = false, overwrite: Boolean = false): ImportResult {
        val byId = scan.found.associateBy { it.id }
        val chosen = selections.map { sel ->
            val variable = requireNotNull(byId[sel.id]) { "no such variable in this scan: ${sel.id}" }
            variable to (sel.scope ?: variable.scope)
        }.distinctBy { it.first.id }
        val stored = chosen.groupBy { it.first.name to it.second }.flatMap { (key, members) -> store(key.first, key.second, members.map { it.first }, overwrite) }
        val replaced = if (replaceSources) replace(scan, stored.filter { it.outcome in SAFE_TO_REPLACE }.map { it.variable }) else Replaced()
        val items = stored.map { ImportResult.Item(it.variable.id, it.variable.name, it.scope.toString(), it.variable.file.toString(), it.outcome, it.variable.id in replaced.ids) }
        val names = items.distinctBy { it.name to it.scope }
        return ImportResult(
            created = names.count { it.outcome == ImportResult.Outcome.CREATED }, updated = names.count { it.outcome == ImportResult.Outcome.UPDATED },
            skipped = names.count { it.outcome.name.startsWith("SKIPPED") }, items = items,
            replacedFiles = replaced.files, backupId = replaced.backupId, notReplaced = replaced.notReplaced,
        )
    }

    private fun store(name: String, scope: SecretScope, members: List<FoundVariable>, overwrite: Boolean): List<Stored> {
        fun all(outcome: ImportResult.Outcome) = members.map { Stored(it, outcome, scope) }
        if (members.map { it.value }.distinct().size > 1) return all(ImportResult.Outcome.SKIPPED_CONFLICT)
        val value = members.first().value
        if (store.holds(name, scope, value)) return all(ImportResult.Outcome.SKIPPED_SAME)
        val exists = store.list().any { it.name == name && it.scope == scope.toString() }
        if (exists && !overwrite) return all(ImportResult.Outcome.SKIPPED_DIFFERS)
        store.set(name, scope, value, source = members.first().file.toString())
        return all(if (exists) ImportResult.Outcome.UPDATED else ImportResult.Outcome.CREATED)
    }

    private fun replace(scan: EnvScanner.Result, variables: List<FoundVariable>): Replaced {
        if (variables.isEmpty()) return Replaced()
        val session = backups.begin()
        val ids = mutableSetOf<String>()
        val notReplaced = mutableListOf<ImportResult.NotReplaced>()
        var files = 0
        for ((file, inFile) in variables.groupBy { it.file }) {
            val reason = rewrite(session, file, inFile, scan.fileHashes[file])
            if (reason == null) {
                ids += inFile.map { it.id }
                files++
            } else notReplaced += ImportResult.NotReplaced(file.toString(), reason)
        }
        return Replaced(ids, files, session.id.takeIf { session.used }, notReplaced)
    }

    /** Null when the file was rewritten, else why not. The copy is on disk before the file changes. */
    private fun rewrite(session: ImportBackups.Session, file: Path, variables: List<FoundVariable>, scanned: String?): String? {
        val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: return "cannot be read"
        if (scanned == null || ValueFingerprint.sha256(bytes) != scanned) return "changed since the scan"
        val text = String(bytes, StandardCharsets.UTF_8)
        if (!text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) return "not valid UTF-8"
        val rewritten = SourceRewriter.rewrite(text, variables).toByteArray(StandardCharsets.UTF_8)
        session.add(file, bytes, ValueFingerprint.sha256(rewritten))
        // A file that refuses the write (locked, read-only) is reported, and the files already rewritten stay under their backup.
        return try {
            AtomicFile.write(file, rewritten)
            null
        } catch (e: IOException) {
            "cannot be written: ${e::class.simpleName}"
        }
    }

    private companion object {
        val SAFE_TO_REPLACE = setOf(ImportResult.Outcome.CREATED, ImportResult.Outcome.UPDATED, ImportResult.Outcome.SKIPPED_SAME)
    }
}
