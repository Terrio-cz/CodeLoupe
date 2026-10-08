package codeloupe.compress

import kotlin.test.Test

class TmpDumpTest {
    @Test
    fun dump() {
        val cases = mapOf("git-status.txt" to listOf("git", "status"), "git-status-short.txt" to listOf("git", "status", "-sb"), "git-log.txt" to listOf("git", "log", "-30"),
            "git-diff-stat.txt" to listOf("git", "diff", "--stat"), "git-diff-patch.txt" to listOf("git", "diff"), "gradle-build.txt" to listOf("./gradlew", "build"),
            "gradle-test-fail.txt" to listOf("./gradlew", "test"), "gradle-compile-error.txt" to listOf("./gradlew", "compileTestKotlin"))
        val out = StringBuilder()
        for ((name, cmd) in cases) {
            val text = javaClass.getResource("/outputs/$name")!!.readText()
            val r = OutputCompressor.compress(cmd, text, "C:/Users/tadea/IdeaProjects/codeloupe-worktrees/CL-92")
            out.appendLine("##### $name ${text.length} -> ${r.text.length} (${r.family}) ${100 - r.text.length * 100 / text.length}% less").appendLine(r.text).appendLine()
        }
        java.io.File(System.getenv("TEMP") + "/compress-dump.txt").writeText(out.toString())
    }
}
