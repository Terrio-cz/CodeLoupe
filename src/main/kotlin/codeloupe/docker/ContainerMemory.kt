package codeloupe.docker

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The memory a container really holds, from one `/containers/{id}/stats` reading: usage without the page cache that the kernel can drop again, as `docker stats` shows it. */
object ContainerMemory {
    fun of(stats: JsonObject): Long? {
        val memory = stats["memory_stats"] as? JsonObject ?: return null
        val usage = memory["usage"]?.jsonPrimitive?.longOrNull ?: return null
        val detail = memory["stats"] as? JsonObject
        // cgroup v2 reports `inactive_file`, v1 `total_inactive_file` or `cache`.
        val cache = listOf("inactive_file", "total_inactive_file", "cache").firstNotNullOfOrNull { detail?.get(it)?.jsonPrimitive?.longOrNull } ?: 0L
        return (usage - cache).coerceAtLeast(0)
    }
}
