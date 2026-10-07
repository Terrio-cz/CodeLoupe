package codeloupe.tracker.youtrack

import codeloupe.tracker.FieldChange
import codeloupe.tracker.FieldValue
import codeloupe.tracker.IssueAttachment
import codeloupe.tracker.IssueComment
import codeloupe.tracker.IssueLink
import codeloupe.tracker.IssueStamp
import codeloupe.tracker.LinkKind
import codeloupe.tracker.TrackerIssue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneOffset

/** YouTrack REST JSON → tracker model. */
internal object YouTrackJson {
    const val ISSUE_FIELDS = "idReadable,summary,description,created,updated,resolved,project(shortName),reporter(login)," +
        "customFields(name,value(name,login,presentation,text,ordinal)),links(direction,linkType(name,sourceToTarget,targetToSource),issues(idReadable))," +
        "comments(id,text,created,updated,author(login),deleted),attachments(id,name,size,mimeType,created,author(login))"
    const val STAMP_FIELDS = "idReadable,updated"
    const val ACTIVITY_FIELDS = "id,timestamp,target(idReadable),field(name),added(name,login,presentation),removed(name,login,presentation),author(login)"

    private const val TYPE = "\$type"

    /** YouTrack's default names of the planning fields; the state field is found by its type. */
    private val PLANNING = setOf("type", "priority", "assignee")

    fun issue(o: JsonObject): TrackerIssue {
        val id = o.text("idReadable") ?: error("issue without idReadable")
        val fields = o.array("customFields").mapNotNull { it as? JsonObject }
        val state = fields.firstOrNull { it.text(TYPE).orEmpty().startsWith("State") }
        fun named(name: String) = fields.firstOrNull { it.text("name").equals(name, ignoreCase = true) }
        val priority = named("priority")
        return TrackerIssue(
            id = id,
            project = o.obj("project")?.text("shortName") ?: id.substringBefore('-'),
            summary = o.text("summary").orEmpty(),
            description = o.text("description").orEmpty(),
            created = o.long("created") ?: 0,
            updated = o.long("updated") ?: 0,
            resolved = o.long("resolved"),
            reporter = o.obj("reporter")?.text("login"),
            state = state?.let { value(it["value"], date = false) },
            type = named("type")?.let { value(it["value"], date = false) },
            priority = priority?.let { value(it["value"], date = false) },
            priorityRank = (priority?.get("value") as? JsonObject)?.long("ordinal")?.toInt(),
            assignee = named("assignee")?.let { value(it["value"], date = false) },
            fields = fields.filter { it !== state && it.text("name").orEmpty().lowercase() !in PLANNING }
                .mapNotNull { f -> value(f["value"], f.text(TYPE) == "DateIssueCustomField")?.let { FieldValue(f.text("name").orEmpty(), it) } },
            links = o.array("links").mapNotNull { it as? JsonObject }.flatMap(::links),
            comments = o.array("comments").mapNotNull { it as? JsonObject }.filter { it.bool("deleted") != true }.map(::comment),
            attachments = o.array("attachments").mapNotNull { it as? JsonObject }.map(::attachment),
        )
    }

    fun stamp(o: JsonObject) = IssueStamp(o.text("idReadable").orEmpty(), o.long("updated") ?: 0)

    fun change(o: JsonObject) = FieldChange(
        id = o.text("id").orEmpty(),
        issue = o.obj("target")?.text("idReadable").orEmpty(),
        at = o.long("timestamp") ?: 0,
        field = o.obj("field")?.text("name").orEmpty(),
        removed = value(o["removed"], date = false).orEmpty(),
        added = value(o["added"], date = false).orEmpty(),
        author = o.obj("author")?.text("login"),
    )

    private fun comment(o: JsonObject) =
        IssueComment(o.text("id").orEmpty(), o.obj("author")?.text("login"), o.long("created") ?: 0, o.long("updated"), o.text("text").orEmpty())

    private fun attachment(o: JsonObject) =
        IssueAttachment(o.text("id").orEmpty(), o.text("name").orEmpty(), o.long("size") ?: 0, o.text("mimeType"), o.long("created") ?: 0, o.obj("author")?.text("login"))

    private fun links(o: JsonObject): List<IssueLink> {
        val type = o.obj("linkType") ?: return emptyList()
        val inward = o.text("direction") == "INWARD"
        val outwardVerb = type.text("sourceToTarget").orEmpty()
        val verb = if (inward) type.text("targetToSource")?.takeIf { it.isNotEmpty() } ?: outwardVerb else outwardVerb
        val kind = kind(type.text("name").orEmpty(), inward)
        return o.array("issues").mapNotNull { (it as? JsonObject)?.text("idReadable") }.map { IssueLink(kind, verb, it) }
    }

    /** YouTrack's built-in link types; any other type keeps its own verb. */
    private fun kind(type: String, inward: Boolean): LinkKind = when (type.lowercase()) {
        "subtask" -> if (inward) LinkKind.PARENT else LinkKind.SUBTASK
        "depend" -> if (inward) LinkKind.DEPENDS_ON else LinkKind.REQUIRED_FOR
        "duplicate" -> if (inward) LinkKind.DUPLICATES else LinkKind.DUPLICATED_BY
        "relates" -> LinkKind.RELATES
        else -> LinkKind.OTHER
    }

    /** A field value as text: users by login, enums by name; null when empty. */
    private fun value(element: JsonElement?, date: Boolean): String? = when (element) {
        null, JsonNull -> null
        is JsonArray -> element.mapNotNull { value(it, date) }.takeIf { it.isNotEmpty() }?.joinToString(", ")
        is JsonObject -> element.text("login") ?: element.text("name") ?: element.text("presentation") ?: element.text("text")
        is JsonPrimitive -> if (date) element.longOrNull?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString() } else element.content
    }?.takeIf { it.isNotEmpty() }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
}
