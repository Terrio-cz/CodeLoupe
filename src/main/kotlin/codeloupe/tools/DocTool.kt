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
    override val description = "A text file without re-reading it: the default is a digest of at most 1000 characters — size, hash, the sections " +
        "by handle with their line counts, the lines that look like errors — then section=[handle or heading prefix, L<from>-<to>] fetches " +
        "just those parts, view=outline lists every section, view=full the whole text. A repeated read with the same root answers " +
        "'unchanged' in one line, or only what changed; since=none reads it again. For plans, brain notes and large persisted tool outputs."
    override val properties = Schema.properties(
        "path" to Schema.string("File, absolute or relative to root (under root or the agent harness's folders), or job:<id>, the full output of a run or job"),
        "view" to Schema.enum(listOf("digest", "outline", "full")),
        "section" to Schema.strings("Section handles or heading prefixes, or line windows like L120-160"),
        "since" to Schema.string("none: read again whatever you already have"),
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
