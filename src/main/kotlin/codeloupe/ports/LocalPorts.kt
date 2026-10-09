package codeloupe.ports

import codeloupe.platform.ToolOutput
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** The ports of this machine: bound and connected to for [inUse], `netstat` / `ss` / `lsof` for [listeners]. */
class LocalPorts(private val os: String = System.getProperty("os.name")) : PortProbe {
    override fun inUse(port: Int): Boolean = !canBind(port) || accepts(port)

    // A wildcard bind fails when any address of the port is taken (the Engine's published ports included).
    private fun canBind(port: Int): Boolean = try {
        ServerSocket(port).use { true }
    } catch (e: java.io.IOException) {
        false
    }

    private fun accepts(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_MS); true }
    } catch (e: java.io.IOException) {
        false
    }

    override fun listeners(): Map<Int, Listener> {
        val parsed = runCatching {
            when {
                os.lowercase().startsWith("windows") -> ListenerParser.windows(output("netstat", "-ano", "-p", "tcp"))
                os.lowercase().startsWith("mac") -> ListenerParser.lsof(output("lsof", "-nP", "-iTCP", "-sTCP:LISTEN", "-Fpn"))
                else -> ListenerParser.ss(output("ss", "-ltnpH"))
            }
        }.getOrDefault(emptyList())
        return parsed.associate { it.port to it.copy(process = it.pid?.let(::describe)) }
    }

    // The command line of a process, or its executable when the OS keeps the arguments from us.
    private fun describe(pid: Long): String? = ProcessHandle.of(pid).map { h -> h.info().commandLine().orElseGet { h.info().command().orElse(null) } }.orElse(null)

    private fun output(vararg command: String): String = ToolOutput.read(TOOL_SECONDS, *command, mergeErrors = true)

    private companion object {
        const val CONNECT_MS = 200
        const val TOOL_SECONDS = 10L
    }
}
