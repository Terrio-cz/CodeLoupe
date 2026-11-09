package codeloupe.cli

import codeloupe.CodeLoupe
import codeloupe.platform.CodeSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * An install of CodeLoupe left lying beside the daemon's data. A deploy that an upgrade replaced stays executable, and
 * that is all it takes for the old daemon to hold the port again: the directory sits next to the data a session is
 * already reading, so one looking for "the installed launcher" finds it and starts it (CL-260). A launcher that does
 * not itself run from such a directory moves it aside, under a name that no longer reads as an install.
 */
object StaleDeploys {
    /** Where a retired deploy goes. Outside `app*`, so a search for a launcher no longer turns it up. */
    const val RETIRED = "retired-deploys"

    /**
     * Moves every obsolete deploy directory of [home] into [RETIRED] and says in one line what became of each. A
     * directory counts when its name starts with `app`, it holds a CodeLoupe launcher or jar, and [runningFrom] (the
     * code this process runs) is not inside it. One whose files are held open — its own daemon is still running — stays
     * where it is and is named as such, so `start` reports it instead of failing on it.
     */
    fun retire(home: Path, runningFrom: String? = CodeSource.of(CodeLoupe::class.java)): List<String> {
        val running = runningFrom?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
        return obsolete(home, running).map { deploy ->
            val target = free(home.resolve(RETIRED), deploy.name)
            runCatching {
                Files.createDirectories(target.parent)
                Files.move(deploy, target)
                "retired the obsolete deploy $deploy -> $target"
            }.getOrElse { e ->
                "could not retire the obsolete deploy $deploy (${e.message ?: e::class.simpleName}): " +
                    "something may still be running from it, and it can start an old daemon again"
            }
        }
    }

    private fun obsolete(home: Path, running: Path?): List<Path> = runCatching {
        Files.list(home).use { entries ->
            entries.asSequence()
                // Only the top level, so a deploy already retired (it lives under [RETIRED]) is not looked at again.
                .filter { it.isDirectory() && it.name.startsWith("app", ignoreCase = true) }
                .filter { isDeploy(it) }
                .filterNot { running != null && running.startsWith(it.toAbsolutePath().normalize()) }
                .sortedBy { it.name }
                .toList()
        }
    }.getOrDefault(emptyList())

    /** A CodeLoupe install as `installDist` and the app bundle lay one out: its jar under `lib`, its launcher under `bin`. */
    private fun isDeploy(dir: Path): Boolean {
        val jar = runCatching {
            Files.list(dir.resolve("lib")).use { it.asSequence().any { f -> f.name.startsWith("codeloupe-") && f.name.endsWith(".jar") } }
        }.getOrDefault(false)
        return jar || Files.isRegularFile(dir.resolve("bin").resolve("codeloupe.bat")) || Files.isRegularFile(dir.resolve("bin").resolve(CodeLoupe.NAME))
    }

    /** [name] under [parent], with a number added while that name is taken: an earlier retirement is never overwritten. */
    private fun free(parent: Path, name: String): Path {
        var candidate = parent.resolve(name)
        var n = 2
        while (Files.exists(candidate)) candidate = parent.resolve("$name-$n").also { n++ }
        return candidate
    }
}
