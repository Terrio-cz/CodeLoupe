package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class IssueCommand : ToolCommand("issue") {
    private val id by argument(help = "Issue id, e.g. TER-5")
    private val full by option(help = "The whole issue instead of the brief").flag()
    private val section by option(help = "Only this part: a description heading, criteria, fields, links, comments, attachments, history").multiple()
    private val since by option(help = "Changes since an ISO time; 'none' shows it again")

    override fun help(context: Context) = "A tracker issue from the local mirror: brief, full, sections or what changed."

    override fun arguments() = mapOf(
        "id" to JsonPrimitive(id), "view" to full.takeIf { it }?.let { JsonPrimitive("full") },
        "sections" to section.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)) }, "since" to since?.let(::JsonPrimitive),
    )
}
