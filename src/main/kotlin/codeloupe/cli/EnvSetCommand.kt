package codeloupe.cli

import codeloupe.config.ConfigLoader
import codeloupe.secrets.SecretScope
import codeloupe.secrets.SecretStore
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option

class EnvSetCommand : CliktCommand(name = "set") {
    private val name by argument(help = "Variable name, e.g. YOUTRACK_TOKEN")
    private val scope by option(help = "global (default), workspace:<id> or repo:<id>").default("global")
    private val source by option(help = "Where it came from (default manual)").default("manual")

    override fun help(context: Context) = "Store or replace a value. It is read from stdin (a hidden prompt on a terminal), never from an argument."

    override fun run() {
        val value = System.console()?.readPassword("value for $name (hidden): ")?.let { String(it) } ?: generateSequence(::readLine).joinToString("\n")
        if (value.isEmpty()) throw UsageError("no value on stdin")
        val meta = try {
            SecretStore.open(ConfigLoader.load().home).set(name, SecretScope.parse(scope), value.trimEnd('\r', '\n'), source)
        } catch (e: IllegalArgumentException) {
            throw UsageError(e.message.orEmpty())
        }
        echo("${if (meta.rotated != null) "replaced" else "stored"} ${meta.name} in ${meta.scope}")
    }
}
