package codeloupe.cli

import codeloupe.config.ConfigLoader
import codeloupe.daemon.Daemon
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import java.net.BindException

class DaemonCommand : CliktCommand(name = "daemon") {
    override fun help(context: Context) = "Run the daemon in the foreground (normally started on demand)."

    override fun run() {
        val config = ConfigLoader.load()
        try {
            Daemon.start(config, exitOnShutdown = true)
        } catch (e: BindException) {
            if (DaemonClient(config).status() == null) throw e
            echo("already running on 127.0.0.1:${config.port}")
            return
        }
        Thread.currentThread().join()
    }
}
