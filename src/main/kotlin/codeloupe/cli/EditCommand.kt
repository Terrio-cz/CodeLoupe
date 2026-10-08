package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class EditCommand : ToolCommand("edit") {
    private val op by argument().help("replace, insert_after, insert_before, insert_member, delete, add_imports, create_file or rename")
    private val name by option(help = "The declaration (Type.member, member(ParamType), pkg.Type, path/File.kt:line)")
    private val hash by option(help = "hash= printed by symbol for that declaration")
    private val code by option(help = "The code (- reads standard input)")
    private val codeFile by option("--code-file", help = "Read the code from this file")
    private val position by option(help = "insert_member: start, end or after_properties")
    private val to by option(help = "rename: the new name")
    private val dryRun by option("--dry-run", help = "rename: plan only").flag()
    private val file by option(help = "add_imports: the file")
    private val import by option("--import", help = "add_imports: a name to import (repeatable)").multiple()
    private val path by option(help = "create_file: the new file")

    override fun help(context: Context) = "Change source by declaration (offered when write.mode allows it): replace, insert, delete, add imports, create a file, rename."

    override fun arguments(): Map<String, JsonElement?> = mapOf(
        "op" to JsonPrimitive(op), "name" to name?.let(::JsonPrimitive), "hash" to hash?.let(::JsonPrimitive), "code" to codeText()?.let(::JsonPrimitive),
        "position" to position?.let(::JsonPrimitive), "to" to to?.let(::JsonPrimitive), "dry_run" to dryRun.takeIf { it }?.let(::JsonPrimitive),
        "file" to file?.let(::JsonPrimitive), "imports" to import.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)) }, "path" to path?.let(::JsonPrimitive),
    )

    private fun codeText(): String? = when {
        codeFile != null -> Files.readString(Path.of(codeFile!!))
        code == "-" -> generateSequence(::readLine).joinToString("\n")
        else -> code
    }.also { if (codeFile != null && code != null) throw UsageError("give --code or --code-file, not both") }
}
