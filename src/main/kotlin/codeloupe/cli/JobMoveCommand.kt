package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class JobMoveCommand : CliktCommand(name = "move") {
    private val id by argument(help = "A queued job")
    private val first by option("--first", help = "To the head of its slot's queue").flag()
    private val before by option("--before", help = "Just before this queued job of the same slot")
    private val after by option("--after", help = "Just after this queued job of the same slot")

    override fun help(context: Context) =
        "Reorder the queue of a slot: move a waiting job first, before or after another one. Running jobs never move."

    override fun run() {
        if (listOf(first, before != null, after != null).count { it } != 1) throw UsageError("give exactly one of --first, --before <id>, --after <id>")
        val body = buildJsonObject {
            if (first) put("first", true)
            before?.let { put("before", it) }
            after?.let { put("after", it) }
        }
        val (status, answer) = DaemonClient(ConfigLoader.load()).send("POST", "/jobs/$id/move", body)
        if (status != 200) {
            echo(answer["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        echo(answer.getValue("text").jsonPrimitive.content)
    }
}
