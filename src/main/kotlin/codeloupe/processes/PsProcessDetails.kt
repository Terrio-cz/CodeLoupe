package codeloupe.processes

import codeloupe.platform.ToolOutput
import java.io.ByteArrayOutputStream

/** macOS (and other Unix without `/proc`): one `ps` for resident sizes and one `lsof` for the working directories of this user's processes. */
internal object PsProcessDetails {
    fun readAll(): Map<Long, ProcessDetails> {
        val rss = residentBytes(run("ps", "-axww", "-o", "pid=,rss="))
        val cwd = workingDirectories(run("lsof", "-a", "-d", "cwd", "-Fpn", "-u", System.getProperty("user.name")))
        return (rss.keys + cwd.keys).associateWith { ProcessDetails(cwd[it], null, rss[it]) }
    }

    /** `ps -o pid=,rss=`: `  4242  51200` with the size in KiB. */
    fun residentBytes(text: String): Map<Long, Long> = text.lines().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size >= 2) parts[0].toLongOrNull()?.let { pid -> parts[1].toLongOrNull()?.let { pid to it * KB } } else null
    }.toMap()

    /** `lsof -Fpn`: a `p4242` line, then the `n/path` line of that process. */
    fun workingDirectories(text: String): Map<Long, String> {
        val cwd = HashMap<Long, String>()
        var pid: Long? = null
        for (line in text.lines()) {
            when {
                line.startsWith("p") -> pid = line.drop(1).toLongOrNull()
                line.startsWith("n") && pid != null -> cwd[pid] = unescape(line.drop(1))
            }
        }
        return cwd
    }

    // Under the C locale lsof prints the bytes of a non-ASCII name as \xHH (UTF-8 bytes of the real name).
    private val ESCAPED_BYTES = Regex("""(?:\\x[0-9a-fA-F]{2})+""")

    internal fun unescape(name: String): String = ESCAPED_BYTES.replace(name) { run ->
        val bytes = ByteArrayOutputStream()
        run.value.split("\\x").drop(1).forEach { bytes.write(it.toInt(HEX)) }
        String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun run(vararg command: String): String = ToolOutput.read(TIMEOUT_S, *command)

    private const val KB = 1024L
    private const val HEX = 16
    private const val TIMEOUT_S = 10L
}
