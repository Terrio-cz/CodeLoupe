package codeloupe.tools

import codeloupe.doc.DocFiles
import codeloupe.doc.DocMemory
import codeloupe.doc.DocReader
import codeloupe.repo.Registry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** `doc`: a text file (a plan, a brain note, a persisted tool output) by digest, by section or line window, and as the delta since the caller last read it. */
class DocTool(
    memory: DocMemory,
    private val files: DocFiles = DocFiles(DocFiles.defaultRoots()),
    /** The file behind a handle such as `job:<id>` (the output of a `run` or `job`), or null when there is none. */
    private val handles: (String) -> Path? = { null },
) : Tool {
    private val reader = DocReader(memory)
    override val name = "doc"
    override val description = "A text file without re-reading: default a digest (size, hash, sections by handle with line counts, error-like lines); " +
        "section=[handle, heading prefix, L<from>-<to>] fetches parts, view=outline lists sections, view=full everything. " +
        "A repeat read answers 'unchanged' or what changed; since=none reads again. For plans, notes, large tool outputs."
    override val properties = Schema.properties(
        "path" to Schema.string("File or job:<id> (a run's full output)"),
        "view" to Schema.enum(listOf("digest", "outline", "full")),
        "section" to Schema.strings("Handles, heading prefixes, L120-160"),
        "since" to Schema.string("none: read again"),
    )
    override val required = listOf("path")
    override val needsRoot = false

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val path = args.string("path").orEmpty().trim()
        val doc = withContext(Dispatchers.IO) {
            if (path.startsWith(JOB)) files.loadLog(path, handles(path) ?: throw IllegalArgumentException("no output kept for $path")) else files.load(root, path)
        }
        val view = when (args.string("view")) {
            "full" -> DocReader.View.FULL
            "outline" -> DocReader.View.OUTLINE
            else -> DocReader.View.DIGEST
        }
        return reader.read(Sessions.key(root), doc, view, args.strings("section"), forget = args.string("since") == "none")
    }

    private companion object {
        const val JOB = "job:"
    }
}
