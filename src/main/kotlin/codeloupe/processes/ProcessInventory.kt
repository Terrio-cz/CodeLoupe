package codeloupe.processes

import codeloupe.platform.IsoTime
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The processes that work in a workspace of the registry, and the memory each workspace holds. Read-only: it lists the
 * process table and changes nothing. A process that belongs to no registered workspace is not in the report at all.
 */
class ProcessInventory(private val workspaces: Workspaces, private val source: ProcessSource = SystemProcesses()) {
    /** [registry]: the workspace list to join with, when the caller has read it already. */
    suspend fun report(registry: WorkspaceList? = null): ProcessReport = withContext(Dispatchers.IO) {
        val list = registry ?: workspaces.recent()
        val processes = try {
            source.read()
        } catch (e: Exception) {
            return@withContext ProcessReport(IsoTime.now(), problems = listOf("$PROBLEM ${e.message.orEmpty().lineSequence().first()}"))
        }
        val entries = ProcessAttribution.attribute(processes, list)
        val ram = entries.groupBy { it.repo to it.workspace }.map { (_, same) ->
            val first = same.first()
            WorkspaceRam(first.repo, first.workspace, first.workspaceState, same.size, same.sumOf { it.rssMb }, same.filter { it.kind.buildTool }.sumOf { it.rssMb })
        }.sortedByDescending { it.rssMb }
        ProcessReport(IsoTime.now(), ram, entries, list.problems)
    }

    /** [list] with the memory and process count of each workspace filled in. */
    suspend fun withRam(list: WorkspaceList): WorkspaceList {
        val ram = report(list).workspaces.associateBy { it.repo.lowercase() to it.workspace.lowercase() }
        return list.copy(
            repos = list.repos.map { repo ->
                repo.copy(
                    workspaces = repo.workspaces.map { w ->
                        val held = ram[repo.name.lowercase() to w.name.lowercase()]
                        w.copy(ramBytes = (held?.rssMb ?: 0) * MB, processes = held?.processes ?: 0)
                    },
                )
            },
        )
    }

    companion object {
        /** Starts the problem that says the process table could not be read: a report without it is not proof that nothing runs. */
        const val PROBLEM = "processes:"
        private const val MB = 1024L * 1024
    }
}
