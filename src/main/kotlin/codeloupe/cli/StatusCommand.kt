package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class StatusCommand : CliktCommand(name = "status") {
    override fun help(context: Context) = "Print the daemon's status as JSON (exit code 3 when it is not running)."

    override fun run() {
        val status = DaemonClient(ConfigLoader.load()).status()
        if (status == null) {
            echo("not running")
            throw ProgramResult(3)
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
