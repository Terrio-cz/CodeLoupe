package codeloupe.cli

import codeloupe.config.Config
import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class StatusCommand(private val load: () -> Config = { ConfigLoader.load() }) : CliktCommand(name = "status") {
    override fun help(context: Context) = "Print the daemon's status as JSON (exit code 3 when it is not running or wedged)."

    override fun run() {
        val client = DaemonClient(load())
        var status = client.status()
        if (status == null) {
            val wedged = client.wedged()
            // No wedged daemon, but a live one: it was only slow.
            if (wedged == null) status = client.status()
            if (status == null) {
                echo(wedged?.let { "wedged: ${it.message}" } ?: "not running")
                throw ProgramResult(3)
            }
        }
        echo(PRETTY.encodeToString(JsonObject.serializer(), status))
    }

    private companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = " "
        }
    }
}
