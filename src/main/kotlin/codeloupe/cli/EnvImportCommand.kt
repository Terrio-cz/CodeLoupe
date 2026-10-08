package codeloupe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** `codeloupe env import …` — find the variables Claude folders hold, move the confirmed ones into the store, undo the source replacement. */
class EnvImportCommand : CliktCommand(name = "import") {
    init {
        subcommands(EnvImportScanCommand(), EnvImportRunCommand(), EnvImportRollbackCommand(), EnvImportBackupsCommand(), EnvImportForgetCommand())
    }

    override fun help(context: Context) =
        "Import existing variables into the store: scan lists names and sources (never values), run imports what you select, rollback restores replaced source files."

    override fun run() = Unit
}
