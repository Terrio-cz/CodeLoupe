package codeloupe.cli

import codeloupe.aot.AotLauncher
import codeloupe.config.ConfigLoader
import codeloupe.daemon.Daemon
import codeloupe.platform.JobObjects
import codeloupe.platform.TerminalSignals
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.net.BindException

class DaemonCommand : CliktCommand(name = "daemon") {
    private val detached by option("--detached", hidden = true).flag()

    // A detached daemon does not inherit its starter's environment (DetachedStart), so these come as arguments.
    private val home by option("--home", hidden = true)
    private val port by option("--port", hidden = true)
    private val root by option("--root", hidden = true)

    override fun help(context: Context) = "Run the daemon in the foreground (normally started on demand)."

    override fun run() {
        val overrides = listOfNotNull(home?.let { "CODELOUPE_HOME" to it }, port?.let { "CODELOUPE_PORT" to it }, root?.let { "CODELOUPE_ROOT" to it })
        val config = ConfigLoader.load(System.getenv() + overrides)
        if (detached) TerminalSignals.ignore()
        JobObjects.enterSelf()
        try {
            Daemon.start(config, exitOnShutdown = true)
        } catch (e: BindException) {
            if (DaemonClient(config).status() == null) throw e
            echo("already running on 127.0.0.1:${config.port}")
            return
        } catch (e: IllegalStateException) {
            throw CliktError(e.message)
        }
        AotLauncher.afterDaemonStart()
        Thread.currentThread().join()
    }
}
