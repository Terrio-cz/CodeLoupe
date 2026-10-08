package codeloupe.cli

import codeloupe.platform.WindowsConsole
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * The CLI always writes UTF-8: the daemon answers UTF-8 and the JVM would otherwise encode stdout with the
 * Windows console code page (cp852/cp1250), turning `…` or `→` into `?`/`�`. A Windows console is switched to the
 * UTF-8 code page so it decodes the same bytes; a pipe or file just receives UTF-8.
 */
object Utf8Output {
    fun install() {
        WindowsConsole.useUtf8()
        System.setOut(printStream(FileOutputStream(FileDescriptor.out)))
        System.setErr(printStream(FileOutputStream(FileDescriptor.err)))
    }

    fun printStream(out: OutputStream): PrintStream = PrintStream(out, true, Charsets.UTF_8)
}
