package codeloupe.repo

import codeloupe.JsonFormat
import codeloupe.daemon.JobQueue
import codeloupe.events.EventTypes
import codeloupe.git.Git
import codeloupe.git.GitObjects
import codeloupe.index.BaseBuilder
import codeloupe.index.BuildResult
import codeloupe.index.FilePut
import codeloupe.index.StoreUpdate
import codeloupe.lang.Languages
import codeloupe.overlay.Overlays
import codeloupe.index.InlineParse
import codeloupe.platform.IsoTime
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Brings a repository's base index to a commit: a full build in a worker while there is no base, else the previous
 * base plus the files that changed — parsed in the daemon when few, in a worker in the heavy lane when many. Each
 * base is a new file swapped in atomically; queries read the old one until then.
 */
internal class BaseBuilds(
    private val queue: JobQueue,
    private val launcher: BuildLauncher,
    private val log: (String) -> Unit,
    /** Called before a base file is deleted or replaced, so nothing keeps it open. */
    private val release: (Path) -> Unit,
    private val swapped: (RepoState) -> Unit,
    private val emit: (String, JsonObject) -> Unit = { _, _ -> },
) {
    fun full(repo: RepoState, commit: String): Deferred<BuildResult> =
        queue.run(JobQueue.Lane.HEAVY, "build:${repo.id}:$commit") {
            build(repo, commit) { tmp -> launcher.build(repo.commonDir, commit, tmp, workDir = repo.dir) }
        }

    /** Starts (or joins) the sync of [repo]'s base to [commit]. Asks git for the changed files once per commit. */
    fun sync(repo: RepoState, commit: String): BaseSync {
        // A sync that ended (failed, or swapped out by a newer one) is not joined: base() decides when to try again.
        synchronized(repo) { repo.sync?.takeIf { it.commit == commit && it.job.isActive }?.let { return it } }
        val from = synchronized(repo) {
            repo.syncTarget = commit
            repo.baseCommit!!
        }
        val planned = changes(repo, from, commit)
        val inline = InlineParse.fits(planned.puts)
        val job = queue.run(if (inline) JobQueue.Lane.FAST else JobQueue.Lane.HEAVY, "sync:${repo.id}:$commit") {
            build(repo, commit, current = { repo.syncTarget == commit }) { tmp ->
                // Copied outside the lock: a base is 100+ MB, and every query of the repository takes that lock to lease a view.
                // Pruning keeps the current and the previous base, so the source outlives one swap; a copy that loses the race
                // fails the sync, which base() retries.
                val (sourceFile, source) = synchronized(repo) { repo.baseFile!! to repo.baseCommit!! }
                Files.copy(sourceFile, tmp, StandardCopyOption.REPLACE_EXISTING)
                val update = if (source == from) planned else changes(repo, source, commit)
                if (inline) {
                    BaseBuilder.update(GitObjects.blobs(repo.commonDir), commit, tmp, update)
                } else {
                    launcher.update(repo.commonDir, commit, tmp, update, repo.dir)
                }
            }
        }
        return BaseSync(commit, inline, job).also { synchronized(repo) { repo.sync = it } }
    }

    private fun changes(repo: RepoState, from: String, to: String): StoreUpdate {
        val changed = Git.diff(repo.commonDir, from, to).filter { Languages.languageOf(it.path) != null }
        val sizes = GitObjects.blobSizes(repo.commonDir, changed.mapNotNull { it.blob })
        return StoreUpdate(
            puts = changed.mapNotNull { entry -> entry.blob?.let { FilePut(entry.path, blob = it, size = sizes[it] ?: 0) } },
            removes = changed.filter { it.blob == null }.map { it.path },
            // A worktree's overlay has usually parsed what a landing brings in.
            factSources = Overlays.stores(repo),
        )
    }

    /** Delete what a killed daemon left behind: half-built bases and update files of build workers. */
    fun clean(repo: RepoState) {
        for (file in repo.dir.listDirectoryEntries()) {
            if (".tmp." in file.name || (file.name.startsWith("update-") && file.name.endsWith(".json"))) runCatching { Files.deleteIfExists(file) }
        }
    }

    /** [current] is false once a newer sync took over: the base it built is then dropped, never swapped in over a newer one. */
    private suspend fun build(
        repo: RepoState,
        commit: String,
        current: () -> Boolean = { true },
        produce: (Path) -> BuildResult,
    ): BuildResult = withContext(Dispatchers.IO) {
        val name = "base-${commit.take(12)}"
        val tmp = repo.dir.resolve("$name.tmp.db")
        val out = repo.dir.resolve("$name.db")
        val result = try {
            produce(tmp)
        } catch (e: Exception) {
            synchronized(repo) {
                repo.failure = BuildFailure(commit, IsoTime.now(), e.message ?: e.toString())
                repo.failedAt = System.currentTimeMillis()
            }
            for (suffix in listOf("", "-wal", "-shm")) runCatching { Files.deleteIfExists(Path.of("$tmp$suffix")) }
            log("build ${repo.id} ${commit.take(7)} failed: ${e.message}")
            emit(
                EventTypes.BUILD_DONE,
                buildJsonObject {
                    put("repo", repo.id)
                    put("commit", commit)
                    put("ok", false)
                    put("error", e.message.orEmpty().take(300))
                },
            )
            throw e
        }
        val swappedIn = synchronized(repo) {
            if (!current()) return@synchronized false
            release(out)
            for (suffix in listOf("", "-wal", "-shm")) Files.deleteIfExists(Path.of("$out$suffix"))
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
            if (repo.baseCommit != commit) {
                repo.previousCommit = repo.baseCommit
                repo.previousFile = repo.baseFile
            }
            repo.baseCommit = commit
            repo.baseFile = out
            repo.lastBuild = LastBuild(IsoTime.now(), result.ok, result.files, result.errors, result.ms, result.peakRssMb)
            repo.failure = null
            prune(repo)
            true
        }
        if (!swappedIn) {
            for (suffix in listOf("", "-wal", "-shm")) runCatching { Files.deleteIfExists(Path.of("$tmp$suffix")) }
            log("build ${repo.id} ${commit.take(7)} dropped: a newer sync took over")
            return@withContext result
        }
        save(repo)
        log("build ${repo.id} ${commit.take(7)}: ${result.files} files in ${result.ms} ms, peak ${result.peakRssMb} MB")
        swapped(repo)
        emit(
            EventTypes.BUILD_DONE,
            buildJsonObject {
                put("repo", repo.id)
                put("commit", commit)
                put("ok", true)
                put("files", result.files)
                put("errors", result.errors)
                put("ms", result.ms)
                result.peakRssMb?.let { put("peakRssMb", it) }
            },
        )
        result
    }

    private fun save(repo: RepoState) {
        Files.writeString(repo.dir.resolve("repo.json"), PRETTY.encodeToString(RepoRecord.serializer(), repo.record()))
    }

    // Called with the repo lock held. Windows refuses to delete a base an in-flight query still has open: best effort,
    // the next build retries.
    private fun prune(repo: RepoState) {
        val keep = listOfNotNull(repo.baseFile?.name, repo.previousFile?.name)
        for (file in repo.dir.listDirectoryEntries("base-*")) {
            // A .tmp file is a build still in progress in the other lane; clean() removes those a killed daemon left.
            if (".tmp." in file.name || keep.any { file.name == it || file.name.startsWith("$it-") }) continue
            release(file)
            runCatching { Files.deleteIfExists(file) }
        }
    }

    private companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val PRETTY = Json(JsonFormat.json) {
            prettyPrint = true
            prettyPrintIndent = " "
        }
    }
}
