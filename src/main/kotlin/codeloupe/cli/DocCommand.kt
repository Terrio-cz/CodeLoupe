package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class DocCommand : ToolCommand("doc") {
    private val path by argument(help = "Text file, absolute or relative to --root")
    private val section by option(help = "Only this section: a handle, a heading prefix or a line window like L120-160").multiple()
    private val view by option(help = "digest (default), outline or full").choice("digest", "outline", "full")
    private val since by option(help = "'none' reads it again even when you already have it")

    override fun help(context: Context) = "A text file by digest, section or line window, and as what changed since your last read."

    override fun arguments() = mapOf(
        "path" to JsonPrimitive(path), "view" to view?.let(::JsonPrimitive),
        "section" to section.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)) }, "since" to since?.let(::JsonPrimitive),
    )
}
