package codeloupe.uiapi

import codeloupe.repo.Registry
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.Workspaces
import java.nio.file.Path
import kotlin.io.path.name

/** A repository the daemon knows or is configured to look after. */
internal data class RepoRef(val id: String, val name: String, val path: String, val commonDir: String, val defaultRef: String)

/**
 * Every repository the UI shows: the ones the registry has indexed plus the configured ones it has not met yet. The
 * workspace scan behind it is shared by the screens that ask within a few seconds of each other.
 */
internal class RepoCatalog(private val registry: Registry, private val workspaces: Workspaces) {
    private val scan = Cached<WorkspaceList>(SCAN_TTL_MS)

    suspend fun scan(): WorkspaceList = scan.get { workspaces.recent() }

    suspend fun repos(): List<RepoRef> {
        val seen = LinkedHashMap<String, RepoRef>()
        for (r in scan().repos) {
            val id = registry.repo(r.commonDir).id
            seen[id] = RepoRef(id, r.name, r.repo, r.commonDir, r.defaultRef)
        }
        for (s in registry.snapshot()) {
            val path = registry.mainWorktree(s.commonDir).toString()
            seen.putIfAbsent(s.id, RepoRef(s.id, Path.of(path).name, path, s.commonDir, s.defaultRef))
        }
        return seen.values.toList()
    }

    private companion object {
        const val SCAN_TTL_MS = 5_000L
    }
}
