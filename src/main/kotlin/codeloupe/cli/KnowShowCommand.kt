package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.know.Fact
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument

class KnowShowCommand : KnowSubcommand("show") {
    private val id by argument(help = "Fact id (k17)")

    override fun help(context: Context) = "One fact in full: text, source, anchors, links, when it was last verified."

    override fun run() {
        val fact = JsonFormat.json.decodeFromJsonElement(Fact.serializer(), call("GET", "/know/${encode(id)}").second)
        echo("${fact.id} ${fact.kind.word} ${fact.scopeRef} [${fact.status.word}${fact.staleReason?.let { ": $it" }.orEmpty()}]")
        echo(fact.text)
        val source = listOfNotNull(fact.source.task, fact.source.run).joinToString(" ")
        echo("source: ${source.ifEmpty { "-" }}  created ${fact.createdAt}  updated ${fact.updatedAt}  verified at ${fact.verifiedAtCommit ?: "-"}")
        fact.anchors.forEach { echo("anchor: ${it.label}  ${if (it.fingerprinted) "fingerprinted" else "existence only"}  (${it.root})") }
        if (fact.supersedes.isNotEmpty()) echo("supersedes: ${fact.supersedes.joinToString(" ")}")
        if (fact.related.isNotEmpty()) echo("related: ${fact.related.joinToString(" ")}")
    }
}
