package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.know.AnchorRef
import codeloupe.know.FactDraft
import codeloupe.know.FactLine
import codeloupe.know.Fact
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KnowPutCommand : KnowSubcommand("put") {
    private val text by argument(help = "The fact, one line").multiple(required = true)
    private val scope by option("--scope", help = "repo, area:<name>, file:<path>, symbol:<Container.name>, task:<id>").required()
    private val kind by option("--kind", help = "decision, pattern, blocker, environment or other").default("other")
    private val task by option("--task", help = "The task the fact came from")
    private val run by option("--run", help = "The run or session that wrote it")
    private val root by option("--root", help = "Checkout the anchors are relative to").default(cwd())
    private val anchors by option("--anchor", help = "Code the fact holds for: path, path#Container.name or Container.name; repeat. A file or symbol scope anchors itself").multiple()
    private val supersedes by option("--supersedes", help = "Id of a fact this one replaces; repeat").multiple()
    private val related by option("--related", help = "Id of a related fact; repeat").multiple()
    private val noVerify by option("--no-verify", help = "Do not fingerprint the anchors now").flag()

    override fun help(context: Context) = "Write a fact. A near-identical fact of the same scope is updated instead of added twice."

    override fun run() {
        val draft = FactDraft(
            scope = scope, text = text.joinToString(" "), kind = kind, task = task, run = run, root = root,
            anchors = anchors.map { AnchorRef.parse(it) }, supersedes = supersedes, related = related, verify = !noVerify,
        )
        val answer = call("POST", "/know", JsonFormat.json.encodeToJsonElement(FactDraft.serializer(), draft).jsonObject).second
        val fact = JsonFormat.json.decodeFromJsonElement(Fact.serializer(), answer.getValue("fact"))
        val replaced = answer.getValue("replaced").jsonPrimitive.content.toInt()
        echo("${answer.getValue("action").jsonPrimitive.content}: ${FactLine.of(fact)}" + if (replaced > 0) "  (superseded $replaced)" else "")
    }
}
