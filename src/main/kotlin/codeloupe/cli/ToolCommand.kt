package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

/** A CLI command that calls one daemon tool and prints its text; exit code 1 when the tool fails. */
abstract class ToolCommand(private val tool: String, commandName: String = tool) : CliktCommand(name = commandName) {
    private val root by option("--root", help = "Repository or worktree to answer for").default(Path.of("").toAbsolutePath().toString())

    abstract fun arguments(): Map<String, JsonElement?>

    override fun run() {
        val args = JsonObject(mapOf("root" to JsonPrimitive(root)) + arguments().filterValues { it != null }.mapValues { it.value!! })
        val outcome = DaemonClient(ConfigLoader.load()).call(tool, args)
        echo(outcome.text)
        if (!outcome.ok) throw ProgramResult(1)
    }
}
