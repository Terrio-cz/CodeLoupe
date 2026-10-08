package codeloupe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** `codeloupe env …` — the secret store: names and scopes in the clear, values only into a process started by `env run`. */
class EnvCommand : CliktCommand(name = "env") {
    init {
        subcommands(EnvListCommand(), EnvSetCommand(), EnvUnsetCommand(), EnvRunCommand(), EnvImportCommand())
    }

    override fun help(context: Context) =
        "Secrets and variables for Claude workspaces: list shows names, set stores one (the value is read from stdin, never an argument), unset removes it, run starts a command with them."

    override fun run() = Unit
}
