package codeloupe.processes

import java.util.concurrent.TimeUnit

/** macOS (and other Unix without `/proc`): one `ps` for resident sizes and one `lsof` for the working directories of this user's processes. */
internal object PsProcessDetails {
    fun readAll(): Map<Long, ProcessDetails> {
        val rss = run("ps", "-axww", "-o", "pid=,rss=").lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) parts[0].toLongOrNull()?.let { pid -> parts[1].toLongOrNull()?.let { pid to it * KB } } else null
        }.toMap()
        val cwd = HashMap<Long, String>()
        var pid: Long? = null
        for (line in run("lsof", "-a", "-d", "cwd", "-Fpn", "-u", System.getProperty("user.name")).lines()) {
            when {
                line.startsWith("p") -> pid = line.drop(1).toLongOrNull()
                line.startsWith("n") && pid != null -> cwd[pid] = line.drop(1)
            }
        }
        return (rss.keys + cwd.keys).associateWith { ProcessDetails(cwd[it], null, rss[it]) }
    }

    private fun run(vararg command: String): String = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) process.destroyForcibly()
        output
    }.getOrDefault("")

    private const val KB = 1024L
    private const val TIMEOUT_S = 10L
}
