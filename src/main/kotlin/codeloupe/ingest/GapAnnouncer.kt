package codeloupe.ingest

import codeloupe.events.EventTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Announces the gaps a pass found, one event per tool, query shape and kind: a busy morning is a few events, not hundreds. */
class GapAnnouncer(private val emit: (String, JsonObject) -> Unit) {
    fun announce(gaps: List<GapRecord>) {
        gaps.filter { it.kind != "busy" }.groupBy { Triple(it.tool, it.shape, it.kind) }.forEach { (key, same) ->
            emit(
                EventTypes.GAP_NEW,
                buildJsonObject {
                    put("tool", key.first)
                    put("shape", key.second)
                    put("kind", key.third)
                    put("count", same.size)
                    same.firstNotNullOfOrNull { it.token }?.let { put("token", it) }
                },
            )
        }
    }
}
