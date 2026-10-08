package codeloupe.docker

import codeloupe.config.Config
import codeloupe.platform.IsoTime
import codeloupe.workspace.WorkspaceState
import codeloupe.workspace.Workspaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every container, image, volume and network of the local Docker Engine, sorted by who owns it and joined with the
 * workspace registry. Read-only: it lists through the Engine API and never changes a resource.
 */
class ResourceInventory(private val config: Config, private val workspaces: Workspaces, private val connect: () -> DockerApi = DockerApi::connect) {
    suspend fun report(): ResourceReport = withContext(Dispatchers.IO) {
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
        val registry = workspaces.list()
        problems += registry.problems
        val states = HashMap<Pair<String, String>, WorkspaceState>()
        for (repo in registry.repos) for (workspace in repo.workspaces) states[repo.name.lowercase() to workspace.name.lowercase()] = workspace.state
        val entries = ResourceClassifier(config.workspaces.adoption) { repo, workspace -> states[repo.lowercase() to workspace.lowercase()] }.classify(objects)
        val counts = entries.groupingBy { "${it.ownership.name.lowercase()}/${it.kind.name.lowercase()}" }.eachCount().toSortedMap()
        ResourceReport(IsoTime.now(), "${api.address} (Docker ${api.version()})", counts, entries, problems)
    }
}
