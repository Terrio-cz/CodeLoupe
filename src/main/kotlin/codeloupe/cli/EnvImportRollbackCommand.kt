package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.secrets.SecretStore
import codeloupe.secrets.imports.ImportBackups
import codeloupe.secrets.imports.ImportLines
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class EnvImportRollbackCommand : CliktCommand(name = "rollback") {
    private val id by argument(help = "The backup id from `env import backups` or the import's result")
    private val force by option("--force", help = "Also restore files that were edited after the import").flag()
    private val json by option("--json", help = "The result as JSON").flag()

    override fun help(context: Context) = "Put back every source file an import replaced. Values stay in the store."

    override fun run() {
        val home = ConfigLoader.load().home
        val result = try {
            ImportBackups(home.resolve("secrets").resolve("import-backups"), SecretStore.open(home)).rollback(id, force)
        } catch (e: IllegalArgumentException) {
            throw UsageError(e.message.orEmpty())
        }
        if (json) echo(JsonFormat.json.encodeToString(ImportBackups.RollbackResult.serializer(), result)) else ImportLines.rolledBack(result).forEach(::echo)
        if (!result.complete) throw ProgramResult(1)
    }
}
