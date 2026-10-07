package codeloupe.index

import codeloupe.JsonFormat
import codeloupe.platform.ProcessMemory
import codeloupe.platform.ProcessPriority
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Child process of the daemon that builds one base index: `BuildWorker <repoDir> <commit> <outFile>`, one JSON
 * line on stdout. Parsing a whole repository is the only heavy work; doing it here keeps the daemon small.
 */
object BuildWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        ProcessPriority.lower()
        val result = try {
            val (repoDir, commit, outFile) = args
            BaseBuilder.build(repoDir, commit, Path.of(outFile)).copy(peakRssMb = ProcessMemory.peakRssMb())
        } catch (e: Exception) {
            BuildResult(ok = false, error = e.message ?: e.toString())
        }
        println(JsonFormat.json.encodeToString(BuildResult.serializer(), result))
        System.out.flush()
        // The PSI environment keeps non-daemon threads alive; the work is done.
        exitProcess(if (result.ok) 0 else 1)
    }
}
