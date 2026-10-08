package codeloupe.uiapi

import codeloupe.events.Event
import codeloupe.events.EventBus
import codeloupe.events.EventTypes
import codeloupe.ingest.Transcripts
import codeloupe.repo.Registry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The notifications of the desktop app, from the event log: finished and failed builds, budget breaches and new gaps. `since`
 * null only reports where the log stands, so an app that starts does not notify the history. A call also lets the transcript
 * ingest look for news, the only thing that makes it run (no timer).
 */
internal class EventFeed(private val events: EventBus, private val registry: Registry, private val transcripts: Transcripts) {
    suspend fun view(since: Long?, limit: Int): EventsView {
        transcripts.fresh()
        val last = events.lastSeq()
        if (since == null) return EventsView(events.epoch(), last, emptyList())
        return EventsView(events.epoch(), last, events.since(since, limit).mapNotNull(::item))
    }

    private fun item(e: Event): EventsView.Item? = when (e.type) {
        EventTypes.BUILD_DONE -> build(e)
        EventTypes.BUDGET_BREACH -> breach(e)
        EventTypes.GAP_NEW -> gap(e)
        else -> null
    }

    private fun build(e: Event): EventsView.Item {
        val repo = e.data.text("repo")
        val name = repo?.let { id -> registry.snapshot().firstOrNull { it.id == id }?.let { registry.mainWorktree(it.commonDir).fileName.toString() } } ?: repo.orEmpty()
        return if (e.data["ok"]?.jsonPrimitive?.booleanOrNull == true) {
            EventsView.Item(e.seq, e.at, EventsView.Kind.BUILD_FINISHED, EventsView.Severity.INFO, "Index built", "$name: ${e.data.int("files")} files in ${e.data.long("ms")} ms", EventsView.Ref("index", repo))
        } else {
            EventsView.Item(e.seq, e.at, EventsView.Kind.BUILD_FAILED, EventsView.Severity.WARNING, "Index build failed", "$name: ${e.data.text("error").orEmpty()}", EventsView.Ref("index", repo))
        }
    }

    private fun breach(e: Event): EventsView.Item {
        val d = e.data
        val used = "${tokens(d.long("weighted"))} of ${tokens(d.long("limit"))} weighted tokens"
        return if (d.text("scope") == "run") {
            val who = listOfNotNull(d.text("role"), d.text("ter")).joinToString(" ")
            EventsView.Item(e.seq, e.at, EventsView.Kind.BUDGET_BREACH, EventsView.Severity.WARNING, "Run budget exceeded", "$who: $used", EventsView.Ref("runs", d.text("runId")))
        } else {
            EventsView.Item(e.seq, e.at, EventsView.Kind.BUDGET_BREACH, EventsView.Severity.WARNING, "Daily budget exceeded", "${d.text("day").orEmpty()}: $used", EventsView.Ref("overview", null))
        }
    }

    private fun gap(e: Event): EventsView.Item {
        val d = e.data
        val example = d.text("token")?.let { " (e.g. $it)" }.orEmpty()
        return EventsView.Item(
            e.seq, e.at, EventsView.Kind.GAP_NEW, EventsView.Severity.INFO, "New gap in ${d.text("tool").orEmpty()}",
            "${d.int("count") ?: 1}× ${d.text("kind").orEmpty()} for ${d.text("shape").orEmpty()}$example", EventsView.Ref("gaps", null),
        )
    }

    private fun tokens(n: Long?): String = when {
        n == null -> "?"
        n >= 1_000_000 -> "%.1fM".format(java.util.Locale.ROOT, n / 1_000_000.0)
        n >= 1_000 -> "%.0fk".format(java.util.Locale.ROOT, n / 1_000.0)
        else -> n.toString()
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
}
