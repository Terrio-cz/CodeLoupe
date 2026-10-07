package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class StopCommand : CliktCommand(name = "stop") {
    private val force by option(help = "Stop even while jobs run; they end and become lost").flag()

    override fun help(context: Context) = "Stop the background daemon."

    override fun run() {
        val stopped = try {
            DaemonClient(ConfigLoader.load()).shutdown(force)
        } catch (e: IllegalStateException) {
            echo(e.message, err = true)
            throw ProgramResult(1)
        }
        echo(if (stopped) "stopped" else "not running")
    }
}
