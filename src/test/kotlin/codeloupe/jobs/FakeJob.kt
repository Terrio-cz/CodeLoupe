package codeloupe.jobs

import codeloupe.platform.JavaProcess
import java.nio.file.Files
import java.nio.file.Path

/**
 * A job command for tests, the same on every OS: `print=<line>`, `sleep=<ms>`, `spin=<ms>` (busy on the CPU), `touch=<file>` in order, then
 * `exit=<code>`. `marker=…` arguments are ignored (they only shape what the policy hook sees).
 */
object FakeJob {
    @JvmStatic
    fun main(args: Array<String>) {
        var exit = 0
        for (arg in args) {
            val (key, value) = arg.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when (key) {
                "print" -> println(value)
                "sleep" -> Thread.sleep(value.toLong())
                "spin" -> {
                    val until = System.nanoTime() + value.toLong() * 1_000_000
                    var sink = 0L
                    while (System.nanoTime() < until) sink += System.nanoTime() and 1
                    if (sink < 0) println(sink)
                }
                "touch" -> Files.writeString(Path.of(value), "done")
                "exit" -> exit = value.toInt()
            }
            System.out.flush()
        }
        System.exit(exit)
    }

    fun command(vararg args: String): List<String> =
        JavaProcess.command(FakeJob::class.java.name, listOf("-Xmx32m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC"), args.toList())
}
