package codeloupe.jobs

import codeloupe.platform.JavaProcess
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A PreToolUse hook for tests: appends what it received (and whether it saw the job's variable SECRET_X) to the file
 * named by its argument, then answers by marker in the command: deny-me, ask-me, crash-me (exit 1), block-me (exit 2);
 * anything else gets no output, which is no objection.
 */
object FakePolicyHook {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = System.`in`.readAllBytes().toString(Charsets.UTF_8)
        val seen = if (System.getenv("SECRET_X") != null) "env-leaked " else ""
        Files.writeString(Path.of(args[0]), seen + input + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        when {
            "deny-me" in input -> decision("deny", "no deploys from tests")
            "ask-me" in input -> decision("ask", "needs a human")
            "crash-me" in input -> System.exit(1)
            "block-me" in input -> {
                System.err.println("blocked by exit code")
                System.exit(2)
            }
        }
        System.exit(0)
    }

    private fun decision(verdict: String, reason: String) {
        println("""{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"$verdict","permissionDecisionReason":"$reason"}}""")
        System.out.flush()
        System.exit(0)
    }

    fun command(record: Path): List<String> =
        JavaProcess.command(FakePolicyHook::class.java.name, listOf("-Xmx32m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC"), listOf(record.toString()))
}
