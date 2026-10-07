package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

class StopCommand : CliktCommand(name = "stop") {
    override fun help(context: Context) = "Stop the background daemon."

    override fun run() = echo(if (DaemonClient(ConfigLoader.load()).shutdown()) "stopped" else "not running")
}
