package codeloupe.ports

import codeloupe.platform.ToolOutput
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** The ports of this machine: bound and connected to for [inUse], `netstat` / `ss` / `lsof` for [listeners]. */
class LocalPorts(private val os: String = System.getProperty("os.name")) : PortProbe {
    override fun inUse(port: Int): Boolean = !canBind(port) || accepts(port)

    // A wildcard bind fails when any address of the port is taken (the Engine's published ports included). Without SO_REUSEADDR
    // switched off, which the JDK turns on outside Windows, BSD and macOS let a wildcard bind through next to a loopback listener.
    private fun canBind(port: Int): Boolean = try {
        ServerSocket().use {
            it.reuseAddress = false
            it.bind(InetSocketAddress(port))
            true
        }
    } catch (e: java.io.IOException) {
        false
    }

    private fun accepts(port: Int): Boolean = LOOPBACKS.any { address ->
        try {
            Socket().use { it.connect(InetSocketAddress(address, port), CONNECT_MS); true }
        } catch (e: java.io.IOException) {
            false
        }
    }

    override fun listeners(): Map<Int, Listener> {
        val parsed = runCatching {
            when {
                os.lowercase().startsWith("windows") -> ListenerParser.windows(output("netstat", "-ano"))
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
        val LOOPBACKS = listOf("127.0.0.1", "::1")
        const val TOOL_SECONDS = 10L
    }
}
