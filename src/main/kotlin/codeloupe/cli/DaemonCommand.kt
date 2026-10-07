package codeloupe.cli

import codeloupe.config.ConfigLoader
import codeloupe.daemon.Daemon
import codeloupe.platform.TerminalSignals
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.net.BindException

class DaemonCommand : CliktCommand(name = "daemon") {
    private val detached by option("--detached", hidden = true).flag()

    override fun help(context: Context) = "Run the daemon in the foreground (normally started on demand)."

    override fun run() {
        val config = ConfigLoader.load()
        if (detached) TerminalSignals.ignore()
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
