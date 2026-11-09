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
    override fun help(context: Context) =
        "Print the daemon's status as JSON (exit code 3 when it is not running or wedged, 4 when the daemon on the port is another version)."

    override fun run() {
        val config = load()
        val client = DaemonClient(config)
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
        // The JSON first: this is the command someone runs to find out what is going on, and it is the daemon's own answer,
        // jar included. Then the verdict, because a daemon of another version answering is a fault and not a status.
        echo(PRETTY.encodeToString(JsonObject.serializer(), status))
        OtherVersion.of(status, config.port)?.let {
            echo(it.refusal, err = true)
            throw ProgramResult(4)
        }
    }

    private companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = " "
        }
    }
}
