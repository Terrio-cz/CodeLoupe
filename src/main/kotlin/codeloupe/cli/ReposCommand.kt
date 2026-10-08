package codeloupe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** `codeloupe repos …` — the repositories the daemon looks after, kept in `config.json` `workspaces.repos`. */
class ReposCommand : CliktCommand(name = "repos") {
    init {
        subcommands(ReposAddCommand(), ReposListCommand())
    }

    override fun help(context: Context) = "Add repositories to the daemon's configuration and have them indexed, or list them."

    override fun run() = Unit
}
