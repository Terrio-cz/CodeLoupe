package codeloupe.tracker

import codeloupe.tracker.youtrack.HttpReply
import codeloupe.tracker.youtrack.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A YouTrack REST fake over a recorded dump (`fixtures/tracker/youtrack-cl.json`): answers the requests
 * [codeloupe.tracker.youtrack.YouTrackAdapter] makes, and lets tests edit issues the way YouTrack would.
 */
class RecordedYouTrack(dump: String = load()) : HttpTransport {
    private val root = Json.parseToJsonElement(dump).jsonObject
    val issues: MutableMap<String, JsonObject> = root["issues"]!!.jsonArray.map { it.jsonObject }.associateByTo(LinkedHashMap()) { id(it) }
    val activities: MutableList<JsonObject> = root["activities"]!!.jsonArray.map { it.jsonObject }.toMutableList()
    val requests: MutableList<String> = CopyOnWriteArrayList()

    /** Ids served by id but left out of project listings, as a listing paged during a delete can do. */
    val unlisted: MutableSet<String> = mutableSetOf()

    /** Old id → the id it moved to; YouTrack answers the old id with the moved issue. */
    val moved: MutableMap<String, String> = mutableMapOf()
    var failActivities = false

    @Synchronized
    override fun get(path: String): HttpReply {
        val decoded = URLDecoder.decode(path, Charsets.UTF_8)
        requests += decoded
        val query = decoded.substringAfter('?', "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val skip = query["\$skip"]?.toInt() ?: 0
        val top = query["\$top"]?.toInt() ?: 42
        return when {
            decoded.startsWith("/api/issues?") -> {
                val project = Regex("project: (\\w+)").find(query["query"].orEmpty())!!.groupValues[1]
                val mine = issues.values.filter { id(it).startsWith("$project-") && id(it) !in unlisted }
                val sorted = if ("updated desc" in query["query"].orEmpty()) mine.sortedByDescending { long(it, "updated") } else mine.sortedBy { long(it, "created") }
                val page = sorted.drop(skip).take(top)
                val stampsOnly = query["fields"] == "idReadable,updated"
                ok(JsonArray(page.map { if (stampsOnly) stamp(it) else it }))
            }
            decoded.startsWith("/api/issues/") -> {
                val asked = decoded.removePrefix("/api/issues/").substringBefore('?')
                val issue = issues[moved[asked] ?: asked] ?: return HttpReply(404, """{"error":"Not Found"}""")
                ok(if (query["fields"] == "updated") buildJsonObject { put("updated", long(issue, "updated")) } else issue)
            }
            decoded.startsWith("/api/activities?") && failActivities -> HttpReply(403, """{"error":"Forbidden","error_description":"no access to activities"}""")
            decoded.startsWith("/api/activities?") -> {
                val start = query["start"]?.toLong() ?: 0
                ok(JsonArray(activities.filter { long(it, "timestamp") >= start }.sortedBy { long(it, "timestamp") }.drop(skip).take(top)))
            }
            else -> HttpReply(400, """{"error":"bad request","error_description":"unexpected $decoded"}""")
        }
    }

    /** Changes [id] as YouTrack would: applies [change] and moves `updated` to [at]. */
    @Synchronized
    fun edit(id: String, at: Long, change: (MutableMap<String, JsonElement>) -> Unit) {
        val issue = issues[id]!!.toMutableMap()
        change(issue)
        issue["updated"] = JsonPrimitive(at)
        issues[id] = JsonObject(issue)
    }

    fun setState(issue: MutableMap<String, JsonElement>, state: String) {
        issue["customFields"] = JsonArray(issue["customFields"]!!.jsonArray.map { f ->
            if (f.jsonObject["name"]!!.jsonPrimitive.content != "State") f
            else JsonObject(f.jsonObject + ("value" to buildJsonObject { put("name", state); put("\$type", "StateBundleElement") }))
        })
    }

    fun addComment(issue: MutableMap<String, JsonElement>, id: String, author: String, at: Long, text: String) {
        val comment = buildJsonObject {
            put("id", id)
            put("text", text)
            put("created", at)
            put("author", buildJsonObject { put("login", author) })
            put("deleted", false)
        }
        issue["comments"] = JsonArray(issue["comments"]!!.jsonArray + comment)
    }

    fun issueRequests(): Int = requests.count { it.startsWith("/api/issues/") && !it.endsWith("fields=updated") }

    private fun ok(body: JsonElement) = HttpReply(200, body.toString())

    private fun stamp(o: JsonObject) = buildJsonObject {
        put("idReadable", id(o))
        put("updated", long(o, "updated"))
    }

    companion object {
        fun load(): String = RecordedYouTrack::class.java.getResource("/fixtures/tracker/youtrack-cl.json")!!.readText()

        fun id(o: JsonObject) = o["idReadable"]!!.jsonPrimitive.content

        fun long(o: JsonObject, key: String) = o[key]!!.jsonPrimitive.long
    }
}
