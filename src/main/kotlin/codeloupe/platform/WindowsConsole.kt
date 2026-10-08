package codeloupe.platform

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** Switches an attached Windows console to UTF-8 output, so the glyphs the tools print survive cp852/cp1250. */
object WindowsConsole {
    /**
     * Sets the output code page to UTF-8 when stdout or stderr is a console, and restores the previous one at exit.
     * Returns false when there is no console (or this is not Windows): the bytes then go to a pipe or file as they are.
     */
    fun useUtf8(): Boolean {
        if (!NativeCalls.isWindows) return false
        return runCatching { switchCodePage() }.getOrDefault(false)
    }

    private fun switchCodePage(): Boolean {
        val getStdHandle = NativeCalls.kernel32("GetStdHandle", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
        val getConsoleMode = NativeCalls.kernel32(
            "GetConsoleMode", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
        val isConsole = Arena.ofConfined().use { arena ->
            val mode = arena.allocate(ValueLayout.JAVA_INT)
            listOf(STD_OUTPUT, STD_ERROR).any { std ->
                (getConsoleMode.invoke(getStdHandle.invoke(std) as MemorySegment, mode) as Int) != 0
            }
        }
        if (!isConsole) return false
        val getCodePage = NativeCalls.kernel32("GetConsoleOutputCP", FunctionDescriptor.of(ValueLayout.JAVA_INT))
        val setCodePage = NativeCalls.kernel32("SetConsoleOutputCP", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
        val previous = getCodePage.invoke() as Int
        if (previous == UTF8) return true
        if ((setCodePage.invoke(UTF8) as Int) == 0) return false
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { setCodePage.invoke(previous) as Int } })
        return true
    }

    private const val UTF8 = 65001
    private const val STD_OUTPUT = -11
    private const val STD_ERROR = -12
}
