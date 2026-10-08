package codeloupe.docker

import java.nio.file.Path

/**
 * The `docker` executable, for the three things only the client implements: compose, build and the full `docker run`
 * command line. It is handed the labels to put on what it creates; it is never used to read anything back.
 */
class DockerCli(private val executable: String = "docker") {
    class Captured(val exit: Int, val stdout: String)

    /** Runs with the terminal of this process and answers the exit code. */
    fun run(args: List<String>, dir: Path): Int =
        ProcessBuilder(listOf(executable) + args).directory(dir.toFile()).inheritIO().start().waitFor()

    /** Runs with its standard output captured (its errors still reach the terminal). */
    fun capture(args: List<String>, dir: Path): Captured {
        val process = ProcessBuilder(listOf(executable) + args).directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT)
            .redirectInput(ProcessBuilder.Redirect.INHERIT).start()
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        return Captured(process.waitFor(), out)
    }
}
