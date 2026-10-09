package codeloupe.platform

import org.junit.jupiter.api.Assumptions.assumeTrue
import sun.misc.Signal
import sun.misc.SignalHandler
import kotlin.test.Test
import kotlin.test.assertSame

class TerminalSignalsTest {
    // Handing a signal the handler it has now gives back the handler it had before, so the check changes nothing.
    private fun current(name: String): SignalHandler {
        val signal = Signal(name)
        val previous = Signal.handle(signal, SignalHandler.SIG_IGN)
        Signal.handle(signal, previous)
        return previous
    }

    @Test
    fun `a daemon ignores the interrupt of the terminal it shares with the CLI`() {
        TerminalSignals.ignore()
        assertSame(SignalHandler.SIG_IGN, current("INT"))
    }

    @Test
    fun `a daemon ignores the hang-up of a closing terminal where there is one`() {
        assumeTrue(!NativeCalls.isWindows, "Windows has no hang-up signal")
        TerminalSignals.ignore()
        assertSame(SignalHandler.SIG_IGN, current("HUP"))
    }
}
