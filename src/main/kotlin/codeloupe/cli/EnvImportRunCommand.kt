package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.secrets.SecretStore
import codeloupe.secrets.imports.EnvImporter
import codeloupe.secrets.imports.EnvScanner
import codeloupe.secrets.imports.ImportBackups
import codeloupe.secrets.imports.ImportLines
import codeloupe.secrets.imports.ImportResult
import codeloupe.secrets.imports.ImportSelection
import codeloupe.secrets.imports.Shadowing
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option

class EnvImportRunCommand : CliktCommand(name = "run") {
    private val select by option("--select", help = "An occurrence id from scan, or ID=scope to store it under another scope (repeat it)").multiple()
    private val allSensitive by option("--all-sensitive", help = "Select every occurrence that looks like a credential").flag()
    private val replace by option("--replace", help = "Then replace the imported values in their source files with references; a backup is kept for rollback").flag()
    private val overwrite by option("--overwrite", help = "Replace a stored value that differs from the file's").flag()
    private val includeExcluded by option("--include-excluded", help = "Also enter folders of excluded systems").flag()
    private val json by option("--json", help = "The result as JSON").flag()

    override fun help(context: Context) =
        "Import the selected variables into the store (created/updated/skipped is reported; rerunning changes nothing). Values are copied in this process and never printed."

    override fun run() {
        val home = ConfigLoader.load().home
        val config = ConfigLoader.envImport(home)
        val scan = EnvScanner(config, home, includeExcluded).scan()
        val store = SecretStore.open(home)
        val shadowing = Shadowing(runCatching { store.list() }.getOrDefault(emptyList()))
        val hiding = if (allSensitive) shadowing.hiding(scan.found.filter { it.sensitive }) else emptyList()
        val selections = select.map(ImportSelection::parse) + if (allSensitive) scan.found.filter { it.sensitive && it !in hiding }.map { ImportSelection(it.id) } else emptyList()
        if (selections.isEmpty() && !allSensitive) throw UsageError("nothing selected: pass --select <id> (ids come from `env import scan`) or --all-sensitive")
        // A variable that would hide a wider secret is imported only when asked for by id: a repository nobody vetted must not replace a key.
        hiding.forEach { echo("not selected: ${it.name} in ${it.file} would hide the ${shadowing.of(it).joinToString(", ")} secret of the same name; import it with --select ${it.id}", err = true) }
        val result = try {
            EnvImporter(store, ImportBackups(home.resolve("secrets").resolve("import-backups"), store)).run(scan, selections, replace, overwrite)
        } catch (e: IllegalArgumentException) {
            throw UsageError(e.message.orEmpty())
        }
        if (json) echo(JsonFormat.json.encodeToString(ImportResult.serializer(), result)) else ImportLines.imported(result).forEach(::echo)
    }
}
