// Where the tests keep their temporary files, and how they are cleaned up (CL-208).
//
// The test classes make hundreds of throwaway repositories, daemon homes and compiler outputs per run, and none of them removes its own:
// 120,000 of them once filled a 930 GB disk. Instead of chasing every helper, the whole run works in one directory of its own (the JVM's
// temp directory and the TEMP/TMP/TMPDIR of the child processes it starts: git, kotlinc, the installed daemon), and that directory is
// removed when the run ends, also when a test failed. A directory that survives a killed build is swept when the next run starts.
//
// CI counts the entries of the system temp directory before and after the run (tools/temp-entries.mjs) and fails above a handful.

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant

val tempRoot: File = File(System.getProperty("java.io.tmpdir"))

// The path stays short for Windows.
val testScratch: File = File(tempRoot, "cl-test-${ProcessHandle.current().pid()}")

// What the test helpers made before the scratch directory existed, and a scratch directory of a build that was killed.
val staleName = Regex(
    "cl-test-\\d+|outside\\d+\\.kt|" +
        "codeloupe-(repo|write-home|java-tools|home|proc|gate|parity|kotlinc|javac|wt|case|write-clone|probe)-.*"
)
val staleAfter: Duration = Duration.ofHours(24)

/** Removes [root] and everything under it, clearing the read-only flag git sets on object files. Returns what is left. */
fun removeTree(root: Path): Int {
    var left = 0
    repeat(3) { attempt ->
        left = 0
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0
        if (attempt > 0) Thread.sleep(500)
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            private fun delete(path: Path) {
                try {
                    Files.deleteIfExists(path)
                } catch (_: IOException) {
                    path.toFile().setWritable(true)
                    try {
                        Files.deleteIfExists(path)
                    } catch (_: IOException) {
                        left++
                    }
                }
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                left++
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        if (left == 0) return 0
    }
    return left
}

val cleanStaleTemp = tasks.register("cleanStaleTemp") {
    description = "Removes what earlier test runs left in the system temp directory more than a day ago."
    doLast {
        val cutoff = Instant.now().minus(staleAfter).toEpochMilli()
        val old = tempRoot.listFiles { f -> staleName.matches(f.name) && f.lastModified() < cutoff }.orEmpty()
        old.forEach { removeTree(it.toPath()) }
        if (old.isNotEmpty()) logger.lifecycle("Removed ${old.size} stale test directories from $tempRoot")
    }
}

val cleanTestScratch = tasks.register("cleanTestScratch") {
    description = "Removes the temporary directory of this test run."
    doLast {
        val left = removeTree(testScratch.toPath())
        if (left > 0) logger.warn("$left entries of $testScratch could not be removed (a process still holds them)")
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(cleanStaleTemp)
    systemProperty("java.io.tmpdir", testScratch.absolutePath)
    // Child processes (git, kotlinc, the installed daemon) take their temp directory from the environment.
    for (name in listOf("TMP", "TEMP", "TMPDIR")) environment(name, testScratch.absolutePath)
    doFirst { testScratch.mkdirs() }
    finalizedBy(cleanTestScratch)
}
