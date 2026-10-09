package codeloupe.secrets.imports

/** The one place an inventory, an import and a rollback become text: names, places and outcomes, never a value. */
object ImportLines {
    fun inventory(report: InventoryReport): List<String> {
        val c = report.counts
        val head = "${c.groups} variables (${c.names} names) in ${c.files} files: ${c.sensitive} look sensitive, ${c.duplicates} duplicated, ${c.conflicts} in conflict, ${c.inStore} already in the store"
        val skipped = "not counted: ${c.references} already references, ${c.empty} empty, ${c.invalidNames} with a name the store cannot hold, ${c.unreadable} unreadable files"
        val excluded = if (report.excluded.isEmpty()) emptyList() else listOf("excluded (use --include-excluded): " + report.excluded.joinToString(", "))
        val rows = report.variables.flatMap { v ->
            val flags = listOfNotNull("sensitive".takeIf { v.sensitive }, "duplicate".takeIf { v.duplicate }, "CONFLICT".takeIf { v.conflict }, "store:${v.store}")
            listOf("${v.name}  ${v.scope}  ${flags.joinToString(" ")}") + v.sources.map { "    ${it.id}  ${it.file}  (${it.kind}: ${it.locator}, #${it.hash})" }
        }
        return listOf(head, skipped) + excluded + rows
    }

    fun imported(result: ImportResult): List<String> {
        val head = "created ${result.created}, updated ${result.updated}, skipped ${result.skipped}" +
            if (result.backupId != null) "; replaced sources in ${result.replacedFiles} files, backup ${result.backupId}" else ""
        val rows = result.items.map { "${it.outcome.name.lowercase().replace('_', ' ')}  ${it.name}  ${it.scope}  ${it.file}" + if (it.replaced) "  (source replaced)" else "" }
        return listOf(head) + rows + result.notReplaced.map { "not replaced: ${it.file} (${it.reason})" }
    }

    fun rolledBack(result: ImportBackups.RollbackResult): List<String> =
        listOf("restored ${result.restored} files, ${result.alreadyOriginal} were already as before" + if (result.complete) "; the backup is gone" else "") +
            result.changedSince.map { "left alone, edited since the import: $it (use --force to restore it anyway)" } +
            result.failed.map { "could not be written back: $it (run the rollback again when it is free)" }
}
