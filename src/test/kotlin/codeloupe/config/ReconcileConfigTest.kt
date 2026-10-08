package codeloupe.config

import codeloupe.docker.ResourceKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconcileConfigTest {
    private fun parse(text: String) = WorkspacesConfig.parse(Json.parseToJsonElement(text).jsonObject).reconcile

    @Test
    fun `nothing runs by itself unless the config says so`() {
        val config = parse("{}")
        assertFalse(config.auto)
        assertEquals(ReconcileConfig(), parse("""{"workspaces":{"reconcile":{}}}"""))
        assertEquals(30, config.intervalMinutes)
        assertEquals(60, config.graceMinutes)
    }

    @Test
    fun `the settings and the protect rules are read, bad ones dropped`() {
        val config = parse(
            """{"workspaces":{"reconcile":{"auto":true,"intervalMinutes":5,"graceMinutes":0,"retryBaseMinutes":2,"retryMaxMinutes":30,
               "protect":[{"match":"^terrio-importer(_|$)"},{"match":"^pg$","kinds":["volume","bogus"]},{"match":"["},{"kinds":["volume"]},3]}}}""",
        )
        assertTrue(config.auto)
        assertEquals(listOf(5, 0, 2, 30), listOf(config.intervalMinutes, config.graceMinutes, config.retryBaseMinutes, config.retryMaxMinutes))
        assertEquals(2, config.protect.size)
        assertTrue(config.protect[0].covers(ResourceKind.NETWORK, listOf("x"), "terrio-importer"))
        assertFalse(config.protect[1].covers(ResourceKind.CONTAINER, listOf("pg"), null))
        assertTrue(config.protect[1].covers(ResourceKind.VOLUME, listOf("pg"), null))
        assertFalse(config.protect[1].covers(null, listOf("pg"), null))
    }

    @Test
    fun `zero or negative minutes fall back to the defaults, except a grace of zero`() {
        val config = parse("""{"workspaces":{"reconcile":{"intervalMinutes":0,"retryBaseMinutes":-1,"graceMinutes":-5}}}""")
        assertEquals(30, config.intervalMinutes)
        assertEquals(1, config.retryBaseMinutes)
        assertEquals(60, config.graceMinutes)
    }
}
