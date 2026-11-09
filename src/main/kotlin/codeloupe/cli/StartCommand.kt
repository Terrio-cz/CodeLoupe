package codeloupe.cli

import codeloupe.config.Config
import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.jsonPrimitive

class StartCommand(
    private val load: () -> Config = { ConfigLoader.load() },
    private val client: (Config) -> DaemonClient = ::DaemonClient,
) : CliktCommand(name = "start") {
    private val force by option(help = "Replace a daemon of another version even while it runs jobs; those jobs end and become lost").flag()

    override fun help(context: Context) =
        "Start the background daemon unless it runs. A wedged one (alive, its port not answering) is ended first, and one of another " +
            "version is replaced; deploy directories an upgrade has left beside the daemon's data are retired."

    override fun run() {
        val config = load()
        StopMarker.clear(config.home)
        val status = try {
            client(config).ensureDaemon(
                endWedged = true,
                replaceOtherVersion = true,
                force = force,
                ended = { echo("ended the wedged daemon pid ${it.pid}: alive, 127.0.0.1:${it.port} did not answer") },
                replacing = { echo("replacing the daemon of another version: ${it.what}") },
            )
        } catch (e: IllegalStateException) {
            // Something the user decides about (jobs that a replacement would end): a sentence, not a stack trace.
            echo(e.message, err = true)
            throw ProgramResult(1)
        }
        // After the start: a deploy whose own daemon was running holds its files open until that process is gone.
        StaleDeploys.retire(config.home).forEach(::echo)
        echo("running pid ${status["pid"]?.jsonPrimitive?.content} on 127.0.0.1:${status["port"]?.jsonPrimitive?.content}")
    }
}
