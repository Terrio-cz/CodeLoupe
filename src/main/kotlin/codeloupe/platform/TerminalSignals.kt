package codeloupe.platform

import sun.misc.Signal
import sun.misc.SignalHandler

/**
 * A daemon started from a CLI shares its terminal: Ctrl+C there (or closing it) would kill the daemon with
 * the CLI. A background daemon ignores those signals and stops only through `/shutdown`.
 */
object TerminalSignals {
    fun ignore() {
        for (name in listOf("INT", "HUP")) {
            runCatching { Signal.handle(Signal(name), SignalHandler.SIG_IGN) }
        }
    }
}
