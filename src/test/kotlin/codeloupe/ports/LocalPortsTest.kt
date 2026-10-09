package codeloupe.ports

import codeloupe.platform.NativeCalls
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalPortsTest {
    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test
    fun `a listener on one loopback address only makes the port in use`() {
        for (address in listOf("127.0.0.1", "::1")) {
            val port = freePort()
            val socket = runCatching { ServerSocket(port, 5, InetAddress.getByName(address)) }.getOrNull() ?: continue
            socket.use { assertTrue(LocalPorts().inUse(port), "$address:$port is taken") }
        }
    }

    @Test
    fun `a listener on the address of a network interface only makes the port in use`() {
        val address = NetworkInterface.getNetworkInterfaces().asSequence().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }.firstOrNull { it is Inet4Address }
        assumeTrue(address != null, "this machine has no network address besides the loopback")
        val port = freePort()
        ServerSocket(port, 5, address).use { assertTrue(LocalPorts().inUse(port), "${address!!.hostAddress}:$port is taken") }
        assertFalse(LocalPorts().inUse(port))
    }

    @Test
    fun `a listener on the IPv6 wildcard makes the port in use`() {
        val port = freePort()
        val socket = runCatching { ServerSocket(port, 5, InetAddress.getByName("::")) }.getOrNull()
        assumeTrue(socket != null, "this machine cannot listen on IPv6")
        socket!!.use { assertTrue(LocalPorts().inUse(port), "[::]:$port is taken") }
    }

    @Test
    fun `a port nobody listens on is free`() {
        assertFalse(LocalPorts().inUse(freePort()))
    }

    @Test
    fun `the OS tool names this process as the owner of a port it listens on`() {
        val tool = if (NativeCalls.isWindows) "netstat" else if (System.getProperty("os.name").lowercase().startsWith("mac")) "lsof" else "ss"
        assumeTrue(runCatching { ProcessBuilder(tool).start().destroyForcibly() }.isSuccess, "$tool is not installed here")
        val port = freePort()
        ServerSocket(port).use {
            val listener = LocalPorts().listeners()[port]
            assertEquals(ProcessHandle.current().pid(), listener?.pid, "listeners: ${LocalPorts().listeners().keys}")
            assertTrue(listener?.process != null, "its command line or executable is shown")
        }
        assertEquals(null, LocalPorts().listeners()[port])
    }
}
