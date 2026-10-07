package codeloupe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** `codeloupe` — on-demand code index for AI coding agents. Root defaults to the current directory. */
class CodeLoupeCommand : CliktCommand(name = "codeloupe") {
    init {
        subcommands(
            DaemonCommand(), StartCommand(), StopCommand(), StatusCommand(),
            FindCommand(), OutlineCommand(), SymbolCommand(), UsagesCommand(), McpConfigCommand(),
        )
    }

    override fun help(context: Context) = "On-demand code index for AI coding agents. Tools take --root (default: the current directory)."

    override fun run() = Unit
}
