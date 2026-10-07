package codeloupe.index

import codeloupe.JsonFormat
import codeloupe.platform.ProcessMemory
import codeloupe.platform.ProcessPriority
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Child process of the daemon that does the heavy parsing, one JSON line on stdout:
 * - `BuildWorker <repoDir> <commit> <outFile>` builds a base index;
 * - `BuildWorker update <repoDir> <commit|-> <dbFile> <updateFile>` applies a [StoreUpdate] (JSON) to a base copy
 *   (with a commit) or to a worktree overlay (`-`).
 * Parsing many files is the only heavy work; doing it here keeps the daemon small.
 */
object BuildWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        ProcessPriority.lower()
        val result = try {
            run(args).copy(peakRssMb = ProcessMemory.peakRssMb())
        } catch (e: Throwable) {
            BuildResult(ok = false, error = e.message ?: e.toString())
        }
        println(JsonFormat.json.encodeToString(BuildResult.serializer(), result))
        System.out.flush()
        // The PSI environment keeps non-daemon threads alive; the work is done.
        exitProcess(if (result.ok) 0 else 1)
    }

    private fun run(args: Array<String>): BuildResult {
        if (args.firstOrNull() != UPDATE) {
            val (repoDir, commit, outFile) = args
            return BaseBuilder.build(repoDir, commit, Path.of(outFile))
        }
        val (_, repoDir, commit, dbFile, updateFile) = args
        val update = JsonFormat.json.decodeFromString(StoreUpdate.serializer(), Files.readString(Path.of(updateFile)))
        if (commit != NO_COMMIT) return BaseBuilder.update(repoDir, commit, Path.of(dbFile), update)
        return Store.open(Path.of(dbFile)).use { StoreUpdater.apply(it, update, repoDir) }
    }

    const val UPDATE = "update"
    const val NO_COMMIT = "-"
}
