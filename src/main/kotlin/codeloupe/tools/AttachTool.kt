package codeloupe.tools

import codeloupe.repo.Registry
import codeloupe.tracker.AttachmentFile
import codeloupe.tracker.Trackers
import java.nio.file.Path

/** `attach`: puts a local file (evidence, a report) on a tracker issue. */
class AttachTool(private val trackers: Trackers) : Tool {
    override val name = "attach"
    override val mutating = true
    override val description = "Attach a local file to a tracker issue (at most 4 MB; an .env file, key or certificate is refused). " +
        "Answers one line: the file name and size. The local mirror lists it at once."
    override val properties = Schema.properties(
        "id" to Schema.string("e.g. TER-5"),
        "file" to Schema.string("Absolute path of the file"),
    )
    override val required = listOf("id", "file")
    override val needsRoot = false

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val id = args.string("id")?.trim().orEmpty()
        val (mirror, canonical) = trackers.mirror(id) ?: return "no tracker mirrors the project of '$id'; mirrored: ${trackers.projects().joinToString(", ")}"
        val path = args.string("file")?.trim().orEmpty().ifEmpty { return "pass the path of the file to attach" }
        val file = Path.of(path)
        if (!file.isAbsolute) return "the file path must be absolute: $path"
        val stored = mirror.attach(canonical, file.fileName.toString(), AttachmentFile.read(file))
        return "$canonical attached ${stored.name} (${size(stored.size)})"
    }

    private fun size(bytes: Long) = if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"
}
