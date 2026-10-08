package codeloupe.cli

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Utf8OutputTest {
    private val glyphs = "… · → ⛔ ‹›"

    @Test
    fun `the print stream encodes UTF-8 whatever the platform charset is`() {
        val bytes = ByteArrayOutputStream()
        Utf8Output.printStream(bytes).println(glyphs)
        assertContentEquals((glyphs + System.lineSeparator()).toByteArray(Charsets.UTF_8), bytes.toByteArray())
    }

    @Test
    fun `a JVM with a legacy console charset still writes UTF-8 to a pipe`() {
        val java = System.getProperty("java.home") + "/bin/java"
        val process = ProcessBuilder(
            java, "--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=windows-1250", "-Dstdout.encoding=windows-1250", "-Dstderr.encoding=windows-1250",
            "-cp", System.getProperty("java.class.path"), "codeloupe.cli.Utf8OutputProbe",
        ).redirectErrorStream(false).start()
        val out = process.inputStream.readBytes()
        val err = process.errorStream.readBytes()
        assertEquals(0, process.waitFor())
        assertContentEquals(glyphs.toByteArray(Charsets.UTF_8), out)
        assertContentEquals(glyphs.toByteArray(Charsets.UTF_8), err)
    }
}

/** Entry point of the child JVM: prints the glyphs the way the CLI's `main` sets up its streams. */
object Utf8OutputProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        Utf8Output.install()
        print("… · → ⛔ ‹›")
        System.err.print("… · → ⛔ ‹›")
    }
}
