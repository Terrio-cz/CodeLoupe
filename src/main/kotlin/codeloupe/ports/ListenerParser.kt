package codeloupe.ports

/** Reads the listening TCP ports and their process ids out of what the OS tools print. */
object ListenerParser {
    private val WINDOWS = Regex("""^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$""", RegexOption.MULTILINE)
    private val SS_PORT = Regex("""^\s*LISTEN\s+\d+\s+\d+\s+\S*?:(\d+)\s+\S+""", RegexOption.MULTILINE)
    private val SS_PID = Regex("""pid=(\d+)""")

    /** `netstat -ano -p tcp` on Windows: `  TCP    0.0.0.0:19002    0.0.0.0:0    LISTENING    4242`. */
    fun windows(text: String): List<Listener> = WINDOWS.findAll(text).map { Listener(it.groupValues[1].toInt(), it.groupValues[2].toLong()) }.distinctBy { it.port to it.pid }.toList()

    /** `ss -ltnpH` on Linux: `LISTEN 0 4096 127.0.0.1:19002 0.0.0.0:* users:(("java",pid=4242,fd=60))`. */
    fun ss(text: String): List<Listener> = text.lineSequence().mapNotNull { line ->
        val port = SS_PORT.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        Listener(port, SS_PID.find(line)?.groupValues?.get(1)?.toLong())
    }.distinctBy { it.port to it.pid }.toList()

    /** `lsof -nP -iTCP -sTCP:LISTEN -Fpn` on macOS: `p4242` then `n*:19002` lines. */
    fun lsof(text: String): List<Listener> {
        val out = ArrayList<Listener>()
        var pid: Long? = null
        for (line in text.lineSequence()) {
            when {
                line.startsWith("p") -> pid = line.drop(1).toLongOrNull()
                line.startsWith("n") -> line.substringAfterLast(':').toIntOrNull()?.let { out += Listener(it, pid) }
            }
        }
        return out.distinctBy { it.port to it.pid }
    }
}
