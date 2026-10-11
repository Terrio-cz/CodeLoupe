package codeloupe.uiapi

import codeloupe.brain.BrainCli
import codeloupe.brain.BrainException
import codeloupe.brain.BrainFacts
import codeloupe.brain.BrainIndexParser
import codeloupe.brain.BrainRoot
import codeloupe.brain.BrainTopicName
import codeloupe.config.WorkspacesConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The Brain screen's data. Workspaces come from CodeLoupe's registry ([BrainRoot.all]) and are named by id; the index and the
 * search are the brain CLI's own answers, only a whole topic is read from its facts file.
 */
internal class BrainViews(private val config: WorkspacesConfig, private val cli: BrainCli = BrainCli()) {
    suspend fun list(): BrainView = coroutineScope {
        BrainView(BrainRoot.all(config).map { root -> async { workspace(root) } }.map { it.await() })
    }

    /**
     * The topic with every entry's metadata, from `brain.mjs show <topic> --json` (subtopics included). An engine that predates `show` falls back to reading the
     * facts file, which has marks and stamps but no ids or tasks.
     */
    suspend fun topic(id: String, topic: String?): BrainTopicView {
        val root = root(id)
        val name = topic?.takeIf { BrainTopicName.valid(it) } ?: throw UiApiException.badRequest("topic must be a topic name of the index")
        return try {
            BrainTopicParser.parse(id, name, cli.run(root.path, listOf("show", name, "--json")))
        } catch (e: BrainException) {
            if ("no facts topic" in e.message.orEmpty()) throw UiApiException.notFound("no topic $name in ${root.name}")
            val found = BrainFacts.read(root.path, name) ?: throw UiApiException.notFound("no topic $name in ${root.name}")
            BrainTopicView(id, name, found.bytes, found.entries.map { BrainTopicView.Entry(it.mark, it.text, it.stamp) })
        }
    }

    suspend fun search(id: String, q: String?, topics: List<String>, tasks: Boolean, limit: String?): BrainSearchView {
        val root = root(id)
        val terms = (q ?: "").split(Regex("\\s+")).map { it.trimStart('-') }.filter { it.isNotEmpty() }.map { it.take(MAX_TERM) }.take(MAX_TERMS)
        if (terms.isEmpty()) throw UiApiException.badRequest("q needs at least one search term")
        val max = limit?.let { it.toIntOrNull()?.takeIf { n -> n in 1..MAX_LIMIT } ?: throw UiApiException.badRequest("limit must be 1..$MAX_LIMIT") } ?: DEFAULT_LIMIT
        topics.firstOrNull { !BrainTopicName.valid(it) }?.let { throw UiApiException.badRequest("topic must be a topic name of the index") }
        val args = listOf("search") + terms + topics.flatMap { listOf("--in", it) } + listOfNotNull("--tasks".takeIf { tasks }) + listOf("--limit", max.toString(), "--json")
        return try {
            BrainSearchParser.parse(id, cli.run(root.path, args))
        } catch (e: BrainException) {
            throw UiApiException.unavailable(e.message.orEmpty())
        }
    }

    private suspend fun workspace(root: BrainRoot): BrainView.Workspace = try {
        val index = BrainIndexParser.parse(cli.run(root.path, listOf("index", "--json")))
        val topics = index.topics.map { BrainView.Topic(it.topic, it.entries, it.bytes, it.about) }
        BrainView.Workspace(root.id, index.workspace?.takeIf { it.isNotBlank() } ?: root.name, root.path.toString().replace('\\', '/'), topics, topics.sumOf { it.entries }, topics.sumOf { it.bytes })
    } catch (e: BrainException) {
        BrainView.Workspace(root.id, root.name, root.path.toString().replace('\\', '/'), emptyList(), 0, 0, e.kind.code, e.message)
    }

    private fun root(id: String): BrainRoot = BrainRoot.find(config, id) ?: throw UiApiException.notFound("no workspace with a brain has id $id")

    private companion object {
        const val MAX_TERMS = 8
        const val MAX_TERM = 80
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}
