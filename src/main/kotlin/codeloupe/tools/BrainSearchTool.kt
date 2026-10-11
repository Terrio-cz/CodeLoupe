package codeloupe.tools

import codeloupe.brain.BrainException
import codeloupe.brain.BrainJson
import codeloupe.brain.BrainRankRequest
import codeloupe.brain.BrainRankResult
import codeloupe.brain.BrainSection
import codeloupe.brain.BrainService
import codeloupe.repo.Registry

/** `brain_search`: whole brain entries for words and files, ranked like `brain_brief` (code proximity above text); JSON `{hits, more}` as `brain.mjs search --json`. */
class BrainSearchTool(private val brain: BrainService) : Tool {
    override val name = "brain_search"
    override val description = "Whole entries of a workspace's shared-memory brain (facts, decisions, findings) for words and files, ranked by code proximity " +
        "(anchors on the files and their symbols, callers, module, tasks that changed that code) above BM25 text; JSON {hits, more} with the id, task, anchors and why each ranked. " +
        "topics narrows to facts topics (subtopics included), tasks=true adds past findings from task notes."
    override val needsRoot = false
    override val properties = Schema.properties(
        "terms" to Schema.strings("words, classes, file names"),
        "files" to Schema.strings("files the question is about (paths or file names)"),
        "topics" to Schema.strings("facts topics to look in; default all"),
        "tasks" to Schema.boolean("also finding lines of past tasks"),
        "workspace" to Schema.string("the workspace directory holding brain/brain.mjs; default the one you work in, else the only one"),
        "limit" to Schema.integer(1, 200),
        "all" to Schema.boolean("also superseded entries"),
        "code_root" to Schema.string("checkout to read the files from; default the workspace's canonical repository"),
    )
    override val required = emptyList<String>()

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val terms = args.strings("terms").map { it.trim().trimStart('-') }.filter { it.isNotEmpty() }.take(MAX_TERMS)
        val files = args.strings("files").map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_TERMS)
        if (terms.isEmpty() && files.isEmpty()) return "pass terms and/or files"
        return try {
            val workspace = brain.pick(args.string("workspace"), root)
            val section = BrainSection("facts", scopes = args.strings("topics"), tasks = args.bool("tasks") == true, limit = args.int("limit")?.coerceIn(1, 200) ?: DEFAULT_LIMIT)
            val result = brain.rank(BrainRankRequest(workspace.path.toString(), files = files, terms = (terms + files).distinct(), codeRoot = args.string("code_root"), all = args.bool("all") == true, sections = listOf(section)))
            val facts = result.sections.getValue("facts")
            BrainJson.compact.encodeToString(Answer.serializer(), Answer(terms + files, facts.hits, facts.more, result.code?.summary))
        } catch (e: BrainException) {
            "brain_search failed: ${e.message}. node brain/brain.mjs search answers without the daemon."
        }
    }

    @kotlinx.serialization.Serializable
    private data class Answer(val terms: List<String>, val hits: List<codeloupe.brain.BrainEntry>, val more: Int, val code: String? = null)

    private companion object {
        const val MAX_TERMS = 60
        const val DEFAULT_LIMIT = 40
    }
}
