package codeloupe.ports

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
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
    fun `a port nobody listens on is free`() {
        assertFalse(LocalPorts().inUse(freePort()))
    }
}
