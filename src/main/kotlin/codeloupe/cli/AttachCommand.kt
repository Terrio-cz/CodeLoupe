package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

class AttachCommand : ToolCommand("attach") {
    private val id by argument(help = "Issue id, e.g. TER-5")
    private val file by argument(help = "The file to attach (at most 4 MB)")

    override fun help(context: Context) = "Attach a local file to a tracker issue; prints the file name and size."

    override fun arguments() = mapOf("id" to JsonPrimitive(id), "file" to JsonPrimitive(Path.of(file).toAbsolutePath().normalize().toString()))
}
