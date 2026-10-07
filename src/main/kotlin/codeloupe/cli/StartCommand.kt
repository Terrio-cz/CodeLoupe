package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import kotlinx.serialization.json.jsonPrimitive

class StartCommand : CliktCommand(name = "start") {
    override fun help(context: Context) = "Start the background daemon unless it runs."

    override fun run() {
        val config = ConfigLoader.load()
        val status = DaemonClient(config).ensureDaemon()
        echo("running pid ${status["pid"]?.jsonPrimitive?.content} on 127.0.0.1:${status["port"]?.jsonPrimitive?.content}")
    }
}
