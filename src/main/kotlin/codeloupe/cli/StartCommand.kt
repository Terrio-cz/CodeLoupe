package codeloupe.cli

import codeloupe.config.Config
import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import kotlinx.serialization.json.jsonPrimitive

class StartCommand(private val load: () -> Config = { ConfigLoader.load() }) : CliktCommand(name = "start") {
    override fun help(context: Context) = "Start the background daemon unless it runs."

    override fun run() {
        val config = load()
        StopMarker.clear(config.home)
        val status = DaemonClient(config).ensureDaemon()
        echo("running pid ${status["pid"]?.jsonPrimitive?.content} on 127.0.0.1:${status["port"]?.jsonPrimitive?.content}")
    }
}
