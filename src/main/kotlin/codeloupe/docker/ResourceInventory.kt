package codeloupe.docker

import codeloupe.config.AdoptionRule
import codeloupe.platform.IsoTime
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every container, image, volume and network of the local Docker Engine, sorted by who owns it and joined with the
 * workspace registry. Read-only: it lists through the Engine API and never changes a resource.
 */
class ResourceInventory(
    private val adoption: List<AdoptionRule>,
    private val recent: suspend () -> WorkspaceList,
    private val connect: () -> DockerApi = DockerApi::connect,
    /** This installation's id ([InstallId]); resources labelled by another one, and containers without ours, are not ours. */
    private val installId: (() -> String)? = null,
) {
    /**
     * [registry]: the workspace list to join with, when the caller has read it already. [memory]: also read the memory of
     * the running containers that belong to a workspace (one Engine reading each, so it is asked for, not always done).
     */
    suspend fun report(registry: WorkspaceList? = null, memory: Boolean = false): ResourceReport = read(memory) { registry ?: recent() }

    /** As [report], with the workspace list still being read: Docker is asked meanwhile and the list awaited only to join. */
    suspend fun report(registry: Deferred<WorkspaceList>, memory: Boolean = false): ResourceReport = read(memory) { registry.await() }

    private suspend fun read(memory: Boolean, registry: suspend () -> WorkspaceList): ResourceReport = withContext(Dispatchers.IO) {
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
            // No engine: the Engine did not give a complete picture, and callers read "engine set" as "these resources are all there are".
            return@withContext ResourceReport(IsoTime.now(), problems = listOf("${api.address}: ${e.message.orEmpty().lineSequence().first()}"))
        }
        val list = registry()
        problems += list.problems
        // Labels name the repository by its folder name: two registered repositories with the same name and workspace but different states
        // cannot be told apart, and one of them must not decide the fate of the other's resources, so that state is left unknown.
        val states = HashMap<Pair<String, String>, WorkspaceState?>()
        for (repo in list.repos) for (workspace in repo.workspaces) {
            val key = repo.name.lowercase() to workspace.name.lowercase()
            states[key] = if (states.containsKey(key) && states[key] != workspace.state) null else workspace.state
        }
        val classified = ResourceClassifier(adoption, installId?.invoke()) { repo, workspace -> states[repo.lowercase() to workspace.lowercase()] }.classify(objects)
        val entries = if (memory) withMemory(api, classified) else classified
        val counts = entries.groupingBy { "${it.ownership.name.lowercase()}/${it.kind.name.lowercase()}" }.eachCount().toSortedMap()
        ResourceReport(IsoTime.now(), "${api.address} (Docker ${runCatching { api.version() }.getOrDefault("?")})", counts, entries, problems)
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
