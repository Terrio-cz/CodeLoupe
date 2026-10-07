package codeloupe.tools

import codeloupe.repo.Registry
import codeloupe.tracker.Trackers
import codeloupe.tracker.read.Parts

/** `issue`: one tracker issue from the local mirror, as a brief, in full, by sections, or as what changed. */
class IssueTool(private val trackers: Trackers) : Tool {
    override val name = "issue"
    override val description = "Tracker issue from the local mirror. view=brief (default: fields, links, criteria, section index) or full; " +
        "sections=[…]: description headings by prefix or criteria, fields, links, comments, attachments, history. A repeated read " +
        "with the same root answers 'unchanged' or only the changes; since=<ISO time> diffs against that time, since=none shows all."
    override val properties = Schema.properties(
        "id" to Schema.string("e.g. TER-5"),
        "view" to Schema.enum(listOf("brief", "full")),
        "sections" to Schema.strings(),
        "since" to Schema.string(),
    )
    override val required = listOf("id")
    override val needsRoot = false

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val id = args.string("id")?.trim().orEmpty()
        val (mirror, canonical) = trackers.mirror(id) ?: return "no tracker mirrors the project of '$id'; mirrored: ${trackers.projects().joinToString(", ")}"
        return trackers.reader.read(mirror, canonical, Parts.of(args.string("view"), args.strings("sections")), args.string("since"), session(root))
    }

    /** The caller's root is its session key: a window works in one worktree. */
    private fun session(root: String) = root.trim().replace('\\', '/').trimEnd('/').lowercase()
}
