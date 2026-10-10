package codeloupe.cli

import codeloupe.know.Know
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KnowSearchCommand : KnowSubcommand("search") {
    private val question by argument(help = "Words or a question").multiple(required = true)
    private val scope by option("--scope", help = "Only these scopes, comma-separated: repo, area:docker, file:src/Foo.kt, symbol:Foo.bar, task:TER-12; a bare `area` takes every area")
    private val kind by option("--kind", help = "Only these kinds, comma-separated: decision, pattern, blocker, environment, other")
    private val limit by option("--limit", help = "At most this many facts").int().default(Know.DEFAULT_LIMIT)
    private val all by option("--all", help = "Also facts that were superseded").flag()

    override fun help(context: Context) = "The best facts for a question, one line each with its id; a fact whose anchored code changed says STALE."

    override fun run() {
        val path = "/know/search?q=${encode(question.joinToString(" "))}&limit=$limit" +
            scope?.let { "&scope=${encode(it)}" }.orEmpty() + kind?.let { "&kind=${encode(it)}" }.orEmpty() + if (all) "&all=1" else ""
        val hits = call("GET", path).second.getValue("hits").jsonArray
        if (hits.isEmpty()) echo("no fact matches") else hits.forEach { echo(it.jsonObject.getValue("line").jsonPrimitive.content) }
    }
}
