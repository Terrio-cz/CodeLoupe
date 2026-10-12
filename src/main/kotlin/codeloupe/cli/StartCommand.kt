package codeloupe.cli

import codeloupe.config.Config
import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import kotlinx.serialization.json.jsonPrimitive

class StartCommand(
    private val load: () -> Config = { ConfigLoader.load() },
    private val client: (Config) -> DaemonClient = ::DaemonClient,
) : CliktCommand(name = "start") {
    override fun help(context: Context) = "Start the background daemon unless it runs; a wedged one (alive, its port not answering) is ended first."

    override fun run() {
        val config = load()
        StopMarker.clear(config.home)
        val status = client(config).ensureDaemon(endWedged = true) { echo("ended the wedged daemon pid ${it.pid}: alive, 127.0.0.1:${it.port} did not answer") }
        echo("running pid ${status["pid"]?.jsonPrimitive?.content} on 127.0.0.1:${status["port"]?.jsonPrimitive?.content}")
    }
}
