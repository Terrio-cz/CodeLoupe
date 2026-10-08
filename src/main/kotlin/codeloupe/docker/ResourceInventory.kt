package codeloupe.docker

import codeloupe.config.Config
import codeloupe.platform.IsoTime
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceState
import codeloupe.workspace.Workspaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every container, image, volume and network of the local Docker Engine, sorted by who owns it and joined with the
 * workspace registry. Read-only: it lists through the Engine API and never changes a resource.
 */
class ResourceInventory(private val config: Config, private val workspaces: Workspaces, private val connect: () -> DockerApi = DockerApi::connect) {
    /**
     * [registry]: the workspace list to join with, when the caller has read it already. [memory]: also read the memory of
     * the running containers that belong to a workspace (one Engine reading each, so it is asked for, not always done).
     */
    suspend fun report(registry: WorkspaceList? = null, memory: Boolean = false): ResourceReport = withContext(Dispatchers.IO) {
        val problems = ArrayList<String>()
        val api = try {
            connect()
        } catch (e: DockerUnavailable) {
            return@withContext ResourceReport(IsoTime.now(), problems = listOf(e.message.orEmpty()))
        }
        val objects = try {
            api.snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ResourceReport(IsoTime.now(), api.address, problems = listOf("${api.address}: ${e.message.orEmpty().lineSequence().first()}"))
        }
        val list = registry ?: workspaces.recent()
        problems += list.problems
        val states = HashMap<Pair<String, String>, WorkspaceState>()
        for (repo in list.repos) for (workspace in repo.workspaces) states[repo.name.lowercase() to workspace.name.lowercase()] = workspace.state
        val classified = ResourceClassifier(config.workspaces.adoption) { repo, workspace -> states[repo.lowercase() to workspace.lowercase()] }.classify(objects)
        val entries = if (memory) withMemory(api, classified) else classified
        val counts = entries.groupingBy { "${it.ownership.name.lowercase()}/${it.kind.name.lowercase()}" }.eachCount().toSortedMap()
        ResourceReport(IsoTime.now(), "${api.address} (Docker ${api.version()})", counts, entries, problems)
    }

    private suspend fun withMemory(api: DockerApi, entries: List<ResourceEntry>): List<ResourceEntry> = coroutineScope {
        entries.map { entry ->
            async {
                val running = entry.kind == ResourceKind.CONTAINER && entry.state == "running" && entry.ownership != OwnershipClass.UNOWNED
                if (running) entry.copy(memoryBytes = runCatching { api.memoryBytes(entry.id) }.getOrNull()) else entry
            }
        }.awaitAll()
    }
}
