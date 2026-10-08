package codeloupe.cli

import codeloupe.config.ConfigLoader
import codeloupe.secrets.SecretScope
import codeloupe.secrets.SecretStore
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option

class EnvUnsetCommand : CliktCommand(name = "unset") {
    private val name by argument(help = "Variable name")
    private val scope by option(help = "global (default), workspace:<id> or repo:<id>").default("global")

    override fun help(context: Context) = "Remove a stored value."

    override fun run() {
        val removed = SecretStore.open(ConfigLoader.load().home).remove(name, SecretScope.parse(scope))
        echo(if (removed) "removed $name from $scope" else "no $name in $scope")
        if (!removed) throw ProgramResult(1)
    }
}
