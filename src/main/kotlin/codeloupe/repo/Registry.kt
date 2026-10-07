package codeloupe.repo

import codeloupe.JsonFormat
import codeloupe.config.Config
import codeloupe.daemon.JobQueue
import codeloupe.git.Git
import codeloupe.git.RefReader
import codeloupe.platform.IsoTime
import codeloupe.platform.Sha1
import codeloupe.query.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Repositories the daemon knows: any path inside a git repository or one of its worktrees maps to one
 * repository (keyed by its git common dir) with one base index of its default branch.
 */
class Registry(
    private val config: Config,
    private val queue: JobQueue,
    private val launcher: BuildLauncher = BuildLauncher(config.buildHeapMb, config.buildTimeoutMs),
    private val log: (String) -> Unit = {},
) {
    private val repos = ConcurrentHashMap<String, RepoState>()
    private val located = ConcurrentHashMap<String, RepoLocation>()

    fun locate(root: String): RepoLocation {
        val key = normalize(root)
        located[key]?.takeIf { System.currentTimeMillis() - it.at < LOCATE_TTL_MS }?.let { return it }
        if (!Path.of(key).exists()) throw IllegalArgumentException("root does not exist: $root")
        val out = Git.run(key, "rev-parse", "--path-format=absolute", "--show-toplevel", "--git-common-dir", allowFail = true)
            ?: throw IllegalArgumentException("not inside a git repository: $root")
        val (worktree, commonDir) = out.trim().lines().map(::normalize)
        return RepoLocation(worktree, commonDir, System.currentTimeMillis()).also { located[key] = it }
    }

    fun repo(commonDir: String): RepoState = repos.computeIfAbsent(commonDir) {
        val id = Sha1.hex(commonDir.lowercase()).take(12)
        val dir = config.home.resolve("repos").resolve(id)
        Files.createDirectories(dir)
        val saved = runCatching { JsonFormat.json.decodeFromString(RepoRecord.serializer(), Files.readString(dir.resolve("repo.json"))) }.getOrNull()
        RepoState(id, dir, commonDir, DefaultRef.of(commonDir)).apply {
            val file = saved?.baseFile?.let { Path.of(it) }
            if (file != null && file.exists()) {
                baseCommit = saved.baseCommit
                baseFile = file
            }
            lastBuild = saved?.lastBuild
        }
    }

    /**
     * Base index for the repository's current default-branch commit. A stale base keeps answering while the new
     * one builds; only a missing base makes the caller wait (bounded by queryTimeoutMs).
     */
    suspend fun base(repo: RepoState): RepoState {
        val head = headOf(repo)
        if (repo.baseCommit == head) return repo
        val job = queue.run(JobQueue.Lane.HEAVY, "build:${repo.id}:$head") { build(repo, head) }
        if (repo.baseFile != null) return repo
        withTimeoutOrNull(config.queryTimeoutMs) { job.await() }
            ?: throw BusyException("indexing ${repo.commonDir} (first build); retry in a few seconds")
        return repo
    }

    suspend fun view(root: String): RepoView {
        val location = locate(root)
        val repo = base(repo(location.commonDir))
        val (baseFile, baseCommit) = synchronized(repo) { repo.baseFile!! to repo.baseCommit!! }
        val worktreeHead = RefReader.head(location.worktree) ?: Git.run(location.worktree, "rev-parse", "HEAD", allowFail = true)?.trim()
        val note = if (worktreeHead != null && worktreeHead != baseCommit) {
            "(index of ${repo.defaultRef}@${baseCommit.take(7)}; this worktree is at ${worktreeHead.take(7)} — its own changes are not indexed yet)"
        } else {
            null
        }
        return RepoView(View(baseFile), repo, location.worktree, note)
    }

    fun snapshot(): List<RepoRecord> = repos.values.map(RepoState::record)

    // Reading the ref files is free; only the git fallback is rate-limited.
    private fun headOf(repo: RepoState): String = RefReader.branch(repo.commonDir, repo.defaultRef) ?: synchronized(repo) {
        if (System.currentTimeMillis() - repo.headAt > HEAD_TTL_MS) {
            repo.head = Git.run(repo.commonDir, "rev-parse", "${repo.defaultRef}^{commit}")!!.trim()
            repo.headAt = System.currentTimeMillis()
        }
        repo.head!!
    }

    private suspend fun build(repo: RepoState, commit: String) = withContext(Dispatchers.IO) {
        val name = "base-${commit.take(12)}"
        val tmp = repo.dir.resolve("$name.tmp.db")
        val out = repo.dir.resolve("$name.db")
        val result = try {
            launcher.build(repo.commonDir, commit, tmp)
        } catch (e: Exception) {
            log("build ${repo.id} ${commit.take(7)} failed: ${e.message}")
            throw e
        }
        for (suffix in listOf("", "-wal", "-shm")) Files.deleteIfExists(Path.of("$out$suffix"))
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
        synchronized(repo) {
            repo.baseCommit = commit
            repo.baseFile = out
            repo.lastBuild = LastBuild(IsoTime.now(), result.ok, result.files, result.errors, result.ms, result.peakRssMb)
        }
        save(repo)
        log("build ${repo.id} ${commit.take(7)}: ${result.files} files in ${result.ms} ms, peak ${result.peakRssMb} MB")
        prune(repo)
        result
    }

    private fun save(repo: RepoState) {
        Files.writeString(repo.dir.resolve("repo.json"), PRETTY.encodeToString(RepoRecord.serializer(), repo.record()))
    }

    // Old bases may still be open by an in-flight query (Windows refuses to delete open files): best effort.
    private fun prune(repo: RepoState) {
        val keep = synchronized(repo) { repo.baseFile }?.name ?: return
        for (file in repo.dir.listDirectoryEntries("base-*")) {
            if (file.name == keep || file.name.startsWith("$keep-")) continue
            runCatching { Files.deleteIfExists(file) }
        }
    }

    private fun normalize(path: String): String = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/')

    private companion object {
        const val LOCATE_TTL_MS = 60_000
        const val HEAD_TTL_MS = 2_000

        @OptIn(ExperimentalSerializationApi::class)
        val PRETTY = Json(JsonFormat.json) {
            prettyPrint = true
            prettyPrintIndent = " "
        }
    }
}
