package codeloupe.reconcile

import codeloupe.JsonFormat
import codeloupe.workspace.WorkspaceRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant

/**
 * The workspaces somebody released: "this workspace's resources are no longer wanted". Marking is a write of one small
 * file and cannot fail on Docker, a lock or a running process; the reconciler does the cleanup afterwards and retries
 * it. A mark covers what the workspace had created up to [Release.at], so a workspace of the same name made later
 * (TER-12 again) is not cleaned by the old mark. A mark goes when nothing of it is left, or after [MAX_AGE].
 */
class ReleaseStore(private val file: Path, private val now: () -> Instant = Instant::now) {
    @Serializable
    data class Release(val repo: String, val workspace: String, val at: String)

    private val items = LinkedHashMap<String, Release>()

    init {
        runCatching { JsonFormat.json.decodeFromString(SERIALIZER, Files.readString(file)).forEach { items[key(it.repo, it.workspace)] = it } }
        // A mark whose time cannot be read is dropped with the old ones: a damaged file must not stop the daemon from starting.
        if (items.values.removeAll { runCatching { Duration.between(Instant.parse(it.at), now()) > MAX_AGE }.getOrDefault(true) }) save()
    }

    /** Marks [ref] released now; releasing again moves the mark to now. */
    @Synchronized
    fun mark(ref: WorkspaceRef): Release {
        val release = Release(ref.repo, ref.workspace, now().toString())
        items[key(ref.repo, ref.workspace)] = release
        save()
        return release
    }

    /** When the workspace was released, or null. */
    @Synchronized
    fun releasedAt(repo: String, workspace: String): Instant? = items[key(repo, workspace)]?.let { instant(it.at) }

    @Synchronized
    fun complete(repo: String, workspace: String) {
        if (items.remove(key(repo, workspace)) != null) save()
    }

    @Synchronized
    fun all(): List<Release> = items.values.toList()

    @Synchronized
    fun isEmpty(): Boolean = items.isEmpty()

    private fun instant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

    private fun key(repo: String, workspace: String) = "${repo.lowercase()}/${workspace.lowercase()}"

    private fun save() {
        runCatching {
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(temp, JsonFormat.json.encodeToString(SERIALIZER, items.values.toList()))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        val MAX_AGE: Duration = Duration.ofDays(30)
        private val SERIALIZER = ListSerializer(Release.serializer())
    }
}
