package codeloupe.docker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContainerMemoryTest {
    private fun of(json: String) = ContainerMemory.of(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `cgroup v2 usage without the inactive file cache`() {
        assertEquals(30_000_000L, of("""{"memory_stats":{"usage":50000000,"stats":{"inactive_file":20000000,"anon":30000000}}}"""))
    }

    @Test
    fun `cgroup v1 names the cache differently`() {
        assertEquals(8L, of("""{"memory_stats":{"usage":10,"stats":{"total_inactive_file":2}}}"""))
        assertEquals(7L, of("""{"memory_stats":{"usage":10,"stats":{"cache":3}}}"""))
    }

    @Test
    fun `usage alone when the Engine gives no detail, never below zero, nothing for a stopped container`() {
        assertEquals(10L, of("""{"memory_stats":{"usage":10}}"""))
        assertEquals(0L, of("""{"memory_stats":{"usage":10,"stats":{"inactive_file":50}}}"""))
        assertNull(of("""{"memory_stats":{}}"""))
        assertNull(of("""{}"""))
    }
}
