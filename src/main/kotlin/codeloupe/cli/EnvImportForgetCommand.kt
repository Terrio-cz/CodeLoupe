package codeloupe.cli

import codeloupe.config.ConfigLoader
import codeloupe.secrets.SecretStore
import codeloupe.secrets.imports.ImportBackups
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument

class EnvImportForgetCommand : CliktCommand(name = "forget") {
    private val id by argument(help = "The backup id")

    override fun help(context: Context) = "Delete an import backup without restoring it; the replaced sources then stay as they are."

    override fun run() {
        val home = ConfigLoader.load().home
        val gone = try {
            ImportBackups(home.resolve("secrets").resolve("import-backups"), SecretStore.open(home)).forget(id)
        } catch (e: IllegalArgumentException) {
            throw UsageError(e.message.orEmpty())
        }
        echo(if (gone) "forgot $id" else "no backup $id")
        if (!gone) throw ProgramResult(1)
    }
}
