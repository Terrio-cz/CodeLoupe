package codeloupe.tracker

import codeloupe.tracker.youtrack.HttpReply
import codeloupe.tracker.youtrack.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

    /** Left out of full-issue pages only: updated again while the pages were read. */
    val missedByPages: MutableSet<String> = mutableSetOf()
    var failActivities = false

    /** Comments are refused with a 400, as a tracker does for a user who may not comment. */
    var failComments = false

    /** The JSON bodies of the POSTs received, in order. */
    val bodies: MutableList<String> = CopyOnWriteArrayList()
    private var writeClock = 1_791_500_000_000L

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
                ok(JsonArray(if (stampsOnly) page.map(::stamp) else page.filter { id(it) !in missedByPages }))
            }
            decoded.startsWith("/api/users/me") -> ok(buildJsonObject { put("login", "dev1") })
            decoded.startsWith("/api/issues/") -> {
                val asked = decoded.removePrefix("/api/issues/").substringBefore('?')
                val issue = issues[moved[asked] ?: asked] ?: return HttpReply(404, """{"error":"Not Found"}""")
                when (query["fields"]) {
                    "updated" -> ok(buildJsonObject { put("updated", long(issue, "updated")) })
                    "project(id)" -> ok(buildJsonObject { put("project", buildJsonObject { put("id", "p-" + id(issue).substringBefore('-')) }) })
                    else -> ok(issue)
                }
            }
            decoded.startsWith("/api/activities?") && failActivities -> HttpReply(403, """{"error":"Forbidden","error_description":"no access to activities"}""")
            decoded.startsWith("/api/activities?") -> {
                val start = query["start"]?.toLong() ?: 0
                ok(JsonArray(activities.filter { long(it, "timestamp") >= start }.sortedBy { long(it, "timestamp") }.drop(skip).take(top)))
            }
            else -> HttpReply(400, """{"error":"bad request","error_description":"unexpected $decoded"}""")
        }
    }

    @Synchronized
    override fun post(path: String, body: String): HttpReply {
        val decoded = URLDecoder.decode(path, Charsets.UTF_8)
        requests += "POST $decoded"
        bodies += body
        if (decoded.startsWith("/api/commands")) return command(Json.parseToJsonElement(body).jsonObject)
        if (decoded.startsWith("/api/issues?")) return create(Json.parseToJsonElement(body).jsonObject)
        val route = decoded.substringBefore('?').removePrefix("/api/issues/")
        val id = route.substringBefore('/')
        if (id !in issues) return HttpReply(404, """{"error":"Not Found"}""")
        val payload = Json.parseToJsonElement(body).jsonObject
        writeClock += 1000
        if (route.endsWith("/comments")) {
            if (failComments) return HttpReply(400, """{"error":"Bad Request","error_description":"no permission to comment"}""")
            val commentId = "${id.removePrefix("CL-")}-${100 + issues[id]!!["comments"]!!.jsonArray.size}"
            edit(id, writeClock) { addComment(it, commentId, "dev1", writeClock, payload["text"]!!.jsonPrimitive.content) }
            return ok(buildJsonObject {
                put("id", commentId)
                put("text", payload["text"]!!.jsonPrimitive.content)
                put("created", writeClock)
                put("author", buildJsonObject { put("login", "dev1") })
                put("deleted", false)
                put("issue", buildJsonObject { put("updated", writeClock) })
            })
        }
        val unknown = payload["customFields"]?.jsonArray.orEmpty().map { it.jsonObject["name"]!!.jsonPrimitive.content }
            .firstOrNull { name -> issues[id]!!["customFields"]!!.jsonArray.none { it.jsonObject["name"]!!.jsonPrimitive.content == name } }
        if (unknown != null) return HttpReply(400, """{"error":"Bad Request","error_description":"unknown field $unknown"}""")
        edit(id, writeClock) { issue ->
            payload["summary"]?.let { issue["summary"] = it }
            payload["description"]?.let { issue["description"] = it }
            val given = payload["customFields"]?.jsonArray.orEmpty().associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["value"]!! }
            issue["customFields"] = JsonArray(issue["customFields"]!!.jsonArray.map { f ->
                val name = f.jsonObject["name"]!!.jsonPrimitive.content
                if (name in given) JsonObject(f.jsonObject + ("value" to given.getValue(name))) else f
            })
        }
        return ok(issues[id]!!)
    }

    /** The files uploaded, as name → bytes, in order. */
    val uploads: MutableList<Pair<String, ByteArray>> = CopyOnWriteArrayList()

    /** The command POSTs refused with a 400, as a tracker does for a link it does not allow. */
    var failCommands = false

    @Synchronized
    override fun postFile(path: String, fileName: String, bytes: ByteArray): HttpReply {
        val decoded = URLDecoder.decode(path, Charsets.UTF_8)
        requests += "POST $decoded"
        val id = decoded.removePrefix("/api/issues/").substringBefore('/')
        if (id !in issues) return HttpReply(404, """{"error":"Not Found"}""")
        uploads += fileName to bytes
        writeClock += 1000
        val stored = buildJsonObject {
            put("id", "12-${300 + uploads.size}")
            put("name", fileName)
            put("size", bytes.size)
            put("mimeType", "application/octet-stream")
            put("created", writeClock)
            put("author", buildJsonObject { put("login", "dev1") })
        }
        edit(id, writeClock) { it["attachments"] = JsonArray(it["attachments"]!!.jsonArray + stored) }
        return ok(JsonArray(listOf(stored)))
    }

    @Synchronized
    private fun create(payload: JsonObject): HttpReply {
        writeClock += 1000
        val project = payload["project"]!!.jsonObject["id"]!!.jsonPrimitive.content.removePrefix("p-")
        val number = 200 + issues.size
        val given = payload["customFields"]!!.jsonArray.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
        val fields = given.values.map { f -> JsonObject(f + ("value" to JsonObject(f["value"]!!.jsonObject + ("\$type" to JsonPrimitive("EnumBundleElement"))))) } +
            buildJsonObject {
                put("name", "State")
                put("\$type", "StateIssueCustomField")
                put("value", buildJsonObject { put("name", "To do") })
            }
        val issue = JsonObject(
            mapOf(
                "idReadable" to JsonPrimitive("$project-$number"), "summary" to payload["summary"]!!, "description" to payload["description"]!!,
                "created" to JsonPrimitive(writeClock), "updated" to JsonPrimitive(writeClock), "resolved" to JsonNull,
                "project" to buildJsonObject { put("shortName", project) }, "reporter" to buildJsonObject { put("login", "dev1") },
                "customFields" to JsonArray(fields), "links" to JsonArray(emptyList()), "comments" to JsonArray(emptyList()), "attachments" to JsonArray(emptyList()),
            ),
        )
        issues["$project-$number"] = issue
        return ok(issue)
    }

    /** `subtask of <epic>` on the issues named: both ends get the link, as YouTrack would. */
    @Synchronized
    private fun command(payload: JsonObject): HttpReply {
        if (failCommands) return HttpReply(400, """{"error":"Bad Request","error_description":"cannot link"}""")
        val epic = Regex("subtask of (\\S+)").find(payload["query"]!!.jsonPrimitive.content)!!.groupValues[1]
        writeClock += 1000
        for (target in payload["issues"]!!.jsonArray.map { it.jsonObject["idReadable"]!!.jsonPrimitive.content }) {
            edit(target, writeClock) { it["links"] = linked(it["links"]!!.jsonArray, "INWARD", epic) }
            edit(epic, writeClock) { it["links"] = linked(it["links"]!!.jsonArray, "OUTWARD", target) }
        }
        return ok(buildJsonObject {})
    }

    private fun linked(links: JsonArray, direction: String, other: String): JsonArray {
        val type = buildJsonObject {
            put("sourceToTarget", "parent for")
            put("targetToSource", "subtask of")
            put("name", "Subtask")
        }
        val found = links.firstOrNull { it.jsonObject["direction"]!!.jsonPrimitive.content == direction && it.jsonObject["linkType"]!!.jsonObject["name"]!!.jsonPrimitive.content == "Subtask" }
        val ref = buildJsonObject { put("idReadable", other) }
        if (found == null) return JsonArray(links + buildJsonObject { put("direction", direction); put("linkType", type); put("issues", JsonArray(listOf(ref))) })
        return JsonArray(links.map { l -> if (l == found) JsonObject(l.jsonObject + ("issues" to JsonArray(l.jsonObject["issues"]!!.jsonArray + ref))) else l })
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
