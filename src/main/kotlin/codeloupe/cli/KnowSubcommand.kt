package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.file.Path

/** A `codeloupe know …` command: talks to the daemon's `/know` routes and ends with exit code 1 on an error answer. */
abstract class KnowSubcommand(name: String) : CliktCommand(name = name) {
    /** The answer of a route; 409 (a verification that could not be made) is an answer too, anything but 200 and 409 ends the command. */
    protected fun call(method: String, path: String, body: JsonObject? = null): Pair<Int, JsonObject> {
        val (status, answer) = DaemonClient(ConfigLoader.load()).send(method, path, body)
        if (status != 200 && status != 409) {
            echo(answer["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        return status to answer
    }

    protected fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)

    protected companion object {
        fun cwd(): String = Path.of("").toAbsolutePath().toString()
    }
}
