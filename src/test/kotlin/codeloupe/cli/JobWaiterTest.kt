package codeloupe.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JobWaiterTest {
    private val done = buildJsonObject {
        put("done", true)
        put("exit", 7)
        put("text", "J1 done exit 7")
    }

    @Test
    fun `a daemon that is restarting is asked again until it answers`() {
        val answers = ArrayDeque<() -> Pair<Int, JsonObject>>(
            listOf(
                { 200 to buildJsonObject { put("done", false) } },
                { throw IOException("connection refused") },
                { throw IllegalStateException("the daemon (pid 1) is alive but does not answer: it is wedged") },
                { 200 to done },
            ),
        )
        assertEquals(7 to "J1 done exit 7", JobWaiter({ answers.removeFirst()() }, retryMs = 1).wait("J1"))
        assertEquals(0, answers.size)
    }

    @Test
    fun `a daemon that stays away ends the wait after the grace time`() {
        val error = assertFailsWith<IllegalStateException> { JobWaiter({ throw IOException("connection refused") }, retryMs = 5, giveUpMs = 50).wait("J1") }
        assertContains(error.message!!, "lost the daemon while waiting for J1")
    }
}
