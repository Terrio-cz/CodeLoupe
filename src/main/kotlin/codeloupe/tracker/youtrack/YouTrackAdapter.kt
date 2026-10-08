package codeloupe.tracker.youtrack

import codeloupe.tracker.FieldChange
import codeloupe.tracker.IssueStamp
import codeloupe.tracker.NewComment
import codeloupe.tracker.TrackerAdapter
import codeloupe.tracker.TrackerException
import codeloupe.tracker.TrackerIssue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTrack REST (`/api`). No dates go into queries (YouTrack reads them in the user's time zone): updates are found
 * by paging newest first, history by the epoch `start` of the activities API. A write answers with the written issue
 * (or comment) itself, so the mirror needs no second request.
 */
class YouTrackAdapter(private val http: HttpTransport) : TrackerAdapter {
    /** Custom field `project/name` (lower case) → its name and `$type`, learned the first time a write names it. */
    private val fieldTypes = ConcurrentHashMap<String, Pair<String, String>>()

    override fun canonical(id: String): Pair<String, String>? =
        id.trim().uppercase().takeIf { ID.matches(it) }?.let { it to it.substringBeforeLast('-') }

    override fun updatedSince(project: String, since: Long?): List<IssueStamp> =
        newestFirst(project, since, YouTrackJson.STAMP_FIELDS, STAMP_PAGE, YouTrackJson::stamp) { it.updated }

    override fun changedSince(project: String, since: Long): List<TrackerIssue> =
        newestFirst(project, since, YouTrackJson.ISSUE_FIELDS, PAGE, YouTrackJson::issue) { it.updated }

    override fun newest(project: String): Long? = updatedPage(project, YouTrackJson.STAMP_FIELDS, 0, 1).firstOrNull()?.let(YouTrackJson::stamp)?.updated

    private fun <T> newestFirst(project: String, since: Long?, fields: String, size: Int, map: (JsonObject) -> T, updated: (T) -> Long): List<T> {
        val out = ArrayList<T>()
        var skip = 0
        while (true) {
            val page = updatedPage(project, fields, skip, size).map(map)
            out += page.filter { since == null || updated(it) >= since }
            if (page.size < size || (since != null && updated(page.last()) < since)) return out
            skip += page.size
        }
    }

    private fun updatedPage(project: String, fields: String, skip: Int, top: Int) =
        list("/api/issues?query=${q("project: ${checkedProject(project)} sort by: updated desc")}&fields=$fields", skip, top)

    override fun page(project: String, skip: Int, top: Int): List<TrackerIssue> =
        list("/api/issues?query=${q("project: ${checkedProject(project)} sort by: created asc")}&fields=${YouTrackJson.ISSUE_FIELDS}", skip, top)
            .map(YouTrackJson::issue)

    override fun issue(id: String): TrackerIssue? =
        getOrNull("/api/issues/${checkedId(id)}?fields=${YouTrackJson.ISSUE_FIELDS}")?.let { YouTrackJson.issue(it as JsonObject) }

    override fun updated(id: String): Long? =
        getOrNull("/api/issues/${checkedId(id)}?fields=updated")?.let { ((it as JsonObject)["updated"] as? JsonPrimitive)?.content?.toLongOrNull() }

    override fun fieldChanges(project: String, since: Long): List<FieldChange> {
        val changes = ArrayList<FieldChange>()
        var skip = 0
        val path = "/api/activities?categories=CustomFieldCategory&issueQuery=${q("project: ${checkedProject(project)}")}&start=$since" +
            "&fields=${YouTrackJson.ACTIVITY_FIELDS}"
        while (true) {
            val page = list(path, skip, ACTIVITY_PAGE)
            changes += page.map(YouTrackJson::change)
            if (page.size < ACTIVITY_PAGE) return changes
            skip += page.size
        }
    }

    override fun update(id: String, fields: Map<String, String>): TrackerIssue {
        val issueId = checkedId(id)
        val own = fields.filterKeys { it.lowercase() in OWN_FIELDS }
        val custom = fields - own.keys
        val body = buildJsonObject {
            own.forEach { (key, text) -> put(key.lowercase(), text) }
            if (custom.isNotEmpty()) put("customFields", JsonArray(custom.map { (name, text) -> customField(issueId, name, text) }))
        }
        return YouTrackJson.issue(post("/api/issues/$issueId?fields=${YouTrackJson.ISSUE_FIELDS}", body))
    }

    override fun comment(id: String, text: String): NewComment {
        val issueId = checkedId(id)
        val reply = post("/api/issues/$issueId/comments?fields=${YouTrackJson.COMMENT_FIELDS}", buildJsonObject { put("text", text) })
        val updated = (reply["issue"] as? JsonObject)?.get("updated")?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }
        return NewComment(YouTrackJson.comment(reply), updated)
    }

    private fun customField(issueId: String, name: String, text: String): JsonObject {
        val project = issueId.substringBefore('-').uppercase()
        val key = "$project/${name.lowercase()}"
        val (exact, type) = fieldTypes[key] ?: learnTypes(issueId, project)[key]
            ?: throw TrackerException("no field '$name' on $issueId")
        return YouTrackFieldBody.customField(exact, type, text)
    }

    private fun learnTypes(issueId: String, project: String): Map<String, Pair<String, String>> {
        val reply = getOrNull("/api/issues/$issueId?fields=customFields(name)") as? JsonObject ?: throw TrackerException("no issue $issueId")
        val types = (reply["customFields"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { f ->
            val name = (f["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val type = (f["\$type"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            "$project/${name.lowercase()}" to (name to type)
        }.toMap()
        fieldTypes.putAll(types)
        return types
    }

    private fun post(path: String, body: JsonObject): JsonObject {
        val reply = http.post(path, body.toString())
        if (reply.status !in 200..299) throw TrackerException("YouTrack HTTP ${reply.status} on POST ${path.substringBefore('?')}${reason(reply.body)}")
        return Json.parseToJsonElement(reply.body) as? JsonObject ?: throw TrackerException("YouTrack returned no object on POST ${path.substringBefore('?')}")
    }

    private fun list(path: String, skip: Int, top: Int): List<JsonObject> {
        val reply = getOrNull("$path&\$skip=$skip&\$top=$top") as? JsonArray ?: throw TrackerException("YouTrack returned no list on ${path.substringBefore('?')}")
        return reply.mapNotNull { it as? JsonObject }
    }

    private fun getOrNull(path: String): JsonElement? {
        val reply = http.get(path)
        if (reply.status == 404) return null
        if (reply.status !in 200..299) throw TrackerException("YouTrack HTTP ${reply.status} on ${path.substringBefore('?')}${reason(reply.body)}")
        return Json.parseToJsonElement(reply.body)
    }

    /** YouTrack's own short error text; its bodies never echo request headers. */
    private fun reason(body: String): String =
        runCatching { ((Json.parseToJsonElement(body) as JsonObject)["error_description"] as JsonPrimitive).content }.getOrNull()
            ?.take(200)?.let { ": $it" }.orEmpty()

    private fun checkedId(id: String): String = id.takeIf { ID.matches(it) } ?: throw TrackerException("not an issue id: $id")

    private fun checkedProject(project: String): String = project.takeIf { PROJECT.matches(it) } ?: throw TrackerException("not a project short name: $project")

    private fun q(text: String) = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        const val STAMP_PAGE = 100
        const val PAGE = 50
        const val ACTIVITY_PAGE = 500
        val OWN_FIELDS = setOf("summary", "description")
        val PROJECT = Regex("[A-Za-z][A-Za-z0-9_]*")
        val ID = Regex("[A-Za-z][A-Za-z0-9_]*-\\d+")
    }
}
