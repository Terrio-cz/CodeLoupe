package codeloupe.cli

import codeloupe.config.ConfigLoader
import codeloupe.secrets.EnvRunner
import codeloupe.secrets.SecretStore
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import java.io.PrintStream
import java.nio.charset.StandardCharsets

class EnvRunCommand : CliktCommand(name = "run") {
    private val workspace by option(help = "Workspace folder or id whose secrets apply")
    private val repository by option("--repo", help = "Repository whose secrets apply")
    private val command by argument(help = "The command, after --: codeloupe env run -- docker compose up").multiple(required = true)

    override fun help(context: Context) =
        "Run a command with the secrets of a workspace and repository in its environment. Its output is masked of every stored value."

    override fun run() {
        val store = SecretStore.open(ConfigLoader.load().home)
        val exit = EnvRunner(store).run(command, workspace, repository, PrintStream(System.out, true, StandardCharsets.UTF_8), PrintStream(System.err, true, StandardCharsets.UTF_8))
        if (exit != 0) throw ProgramResult(exit)
    }
}
