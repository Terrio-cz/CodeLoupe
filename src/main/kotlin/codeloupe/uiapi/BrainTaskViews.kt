package codeloupe.uiapi

import codeloupe.brain.BrainEntry
import codeloupe.brain.BrainException
import codeloupe.brain.BrainIndex
import codeloupe.brain.BrainRoot
import codeloupe.brain.BrainTaskFiles
import codeloupe.brain.BrainTaskNotes
import codeloupe.config.WorkspacesConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException

/**
 * The brains' task notes for the task detail and the Brain screen's Tasks view. The notes are read from the task files, so a cold or failing brain
 * index never delays them: the index only adds the superseded marks, and while it is not built the section says so ([TaskBrain.Notes.indexing]).
 */
internal class BrainTaskViews(
    private val config: WorkspacesConfig,
    /** The brain index as last built, or null while it is cold (and then starts building). */
    private val index: (BrainRoot) -> BrainIndex?,
    private val tracked: (String) -> Boolean,
    private val files: BrainTaskFiles = BrainTaskFiles(),
) {
    suspend fun of(task: String): TaskBrain = coroutineScope {
        TaskBrain(BrainRoot.all(config).map { root -> async { notes(root, task) } }.mapNotNull { it.await() })
    }

    suspend fun list(workspaceId: String): BrainTasksView {
        val root = BrainRoot.find(config, workspaceId) ?: throw UiApiException.notFound("no workspace with a brain has id $workspaceId")
        return BrainTasksView(root.id, files.summaries(root.path).map { BrainTasksView.Task(it.id, it.lastAt, stateOf(it.state), it.entries, tracked(it.id)) })
    }

    private suspend fun notes(root: BrainRoot, task: String): TaskBrain.Notes? {
        val found = try {
            files.read(root.path, task) ?: return null
        } catch (e: BrainException) {
            return failed(root, e.message)
        } catch (e: IOException) {
            return failed(root, "${task}.md could not be read: ${e.message.orEmpty().take(MAX_ERROR)}")
        }
        val built = index(root)
        val covered = built?.snapshot?.findings.orEmpty().filter { it.task == task && it.superseded }.associateBy { it.line }
        return TaskBrain.Notes(
            workspaceId = root.id, workspace = root.name, state = stateOf(found.state), entries = entries(found, covered),
            entryCount = found.entries.size, lastAt = found.lastAt, indexing = built == null,
        )
    }

    private fun entries(found: BrainTaskNotes, covered: Map<Int, BrainEntry>): List<TaskBrain.Entry> =
        found.entries.asReversed().take(TaskBrain.MAX_ENTRIES).map {
            val mark = covered[it.line]
            TaskBrain.Entry(it.marker, it.text.take(TaskBrain.MAX_TEXT), it.who, it.at, it.line, it.rejected, mark != null, mark?.coveredBy)
        }

    private fun failed(root: BrainRoot, message: String?) =
        TaskBrain.Notes(root.id, root.name, emptyList(), emptyList(), 0, null, error = message.orEmpty().take(MAX_ERROR))

    private fun stateOf(state: Map<String, String>) = state.map { TaskBrain.StateKey(it.key, it.value) }

    private companion object {
        const val MAX_ERROR = 300
    }
}
