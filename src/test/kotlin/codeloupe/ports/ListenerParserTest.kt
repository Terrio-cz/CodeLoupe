package codeloupe.ports

import codeloupe.config.PortsConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ListenerParserTest {
    @Test
    fun `netstat of Windows gives the listening ports and their pids, and ignores other states`() {
        val text = """
            Active Connections

              Proto  Local Address          Foreign Address        State           PID
              TCP    0.0.0.0:135            0.0.0.0:0              LISTENING       1180
              TCP    0.0.0.0:19002          0.0.0.0:0              LISTENING       4242
              TCP    127.0.0.1:19003        0.0.0.0:0              LISTENING       5252
              TCP    [::]:19002             [::]:0                 LISTENING       4242
              TCP    127.0.0.1:50221        127.0.0.1:19002        ESTABLISHED     7777
        """.trimIndent()
        assertEquals(listOf(135 to 1180L, 19002 to 4242L, 19003 to 5252L), ListenerParser.windows(text).map { it.port to it.pid })
    }

    @Test
    fun `ss of Linux gives ports and pids, with or without the owner`() {
        val text = """
            LISTEN 0      4096       127.0.0.1:19002      0.0.0.0:*    users:(("java",pid=4242,fd=60))
            LISTEN 0      511                *:8080            *:*
            LISTEN 0      128             [::]:22           [::]:*    users:(("sshd",pid=900,fd=4))
        """.trimIndent()
        assertEquals(listOf(19002 to 4242L, 8080 to null, 22 to 900L), ListenerParser.ss(text).map { it.port to it.pid })
    }

    @Test
    fun `lsof of macOS gives ports under their pids`() {
        val text = "p4242\nn*:19002\np900\nn127.0.0.1:22\nn[::1]:2222\n"
        assertEquals(listOf(19002 to 4242L, 22 to 900L, 2222 to 900L), ListenerParser.lsof(text).map { it.port to it.pid })
    }

    @Test
    fun `the range is read from the config, a bad one means none`() {
        fun parse(text: String) = PortsConfig.parse(Json.parseToJsonElement(text).jsonObject["ports"]?.jsonObject)
        assertEquals(19000..19999, parse("""{"ports":{"range":[19000,19999]}}""").range)
        assertEquals(null, parse("""{"ports":{"range":[19999,19000]}}""").range)
        assertEquals(null, parse("""{"ports":{"range":[80,90]}}""").range)
        assertEquals(null, parse("""{"ports":{"range":[19000]}}""").range)
        assertEquals(null, parse("""{}""").range)
    }
}
