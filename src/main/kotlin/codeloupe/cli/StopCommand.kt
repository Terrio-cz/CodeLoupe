package codeloupe.cli

import codeloupe.aot.AotLauncher
import codeloupe.config.Config
import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class StopCommand(private val load: () -> Config = { ConfigLoader.load() }) : CliktCommand(name = "stop") {
    private val force by option(help = "Stop even while jobs run; they end and become lost").flag()

    override fun help(context: Context) = "Stop the background daemon; the desktop app leaves it stopped until `start`."

    override fun run() {
        val config = load()
        // A training holds the jars open, which an update cannot replace.
        AotLauncher.cancel(config.home)
        // Before the daemon goes down, so the app never sees a stopped daemon without the marker.
        StopMarker.write(config.home)
        val stopped = try {
            DaemonClient(config).shutdown(force)
        } catch (e: IllegalStateException) {
            StopMarker.clear(config.home)
            echo(e.message, err = true)
            throw ProgramResult(1)
        }
        echo(if (stopped) "stopped" else "not running")
    }
}
