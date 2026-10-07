package codeloupe

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Throwaway git repositories made from the test fixtures. */
object TestRepos {
    val FIXTURES: Path = Path.of(TestRepos::class.java.getResource("/fixtures")!!.toURI())

    fun tmpDir(prefix: String): Path = Files.createTempDirectory("codeloupe-$prefix-")

    fun git(dir: Path, vararg args: String): String {
        val command = listOf("git", "-C", dir.toString(), "-c", "user.email=t@example.com", "-c", "user.name=t", "-c", "core.autocrlf=false") + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out.trim()
    }

    /** A git repository holding a copy of a fixture tree, committed on branch main. */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun fixtureRepo(name: String, extraFiles: Map<String, String> = emptyMap()): Path {
        val dir = tmpDir("repo")
        FIXTURES.resolve(name).copyToRecursively(dir, followLinks = false, overwrite = true)
        for ((path, text) in extraFiles) dir.resolve(path).also { it.parent.createDirectories() }.writeText(text)
        git(dir, "init", "-q", "-b", "main")
        git(dir, "add", "-A")
        git(dir, "commit", "-q", "-m", "fixture")
        return dir
    }

    /** A Kotlin class with [n] small members — big enough to trigger the large-type summary. */
    fun bigClass(pkg: String, name: String, n: Int): String {
        val members = (0 until n).joinToString("\n") { i -> "    fun m$i(x: Int): Int {\n        return x + $i\n    }\n" }
        return "package $pkg\n\n/** A big class. */\nclass $name {\n$members}\n"
    }

    val SAMPLE_WITH_BIG = mapOf("big/src/main/kotlin/com/example/big/Big.kt" to bigClass("com.example.big", "Big", 40))
}
