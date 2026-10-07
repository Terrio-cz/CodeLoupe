package codeloupe.git

import codeloupe.platform.IdleTimer
import codeloupe.platform.TimedPart
import codeloupe.platform.Timings
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines

/**
 * Repositories read in-process with JGit, one per git common dir, opened on first use and closed after [IDLE_MS]
 * unused: an open repository holds its pack files, and Windows refuses to let the user's `git gc` delete a pack
 * that is open. First use also installs [ReadOnlySystemReader] for the whole process.
 */
object JGitRepos {
    /** Long enough for one burst of reads (a `changes` call, a base sync) to share the open packs. */
    private const val IDLE_MS = 1_000L

    /**
     * JGit holds every pack index in the heap (~28 B per object, beyond its window cache). Above this much index
     * (~600k objects) the daemon's small heap is left alone and git answers instead.
     */
    private const val MAX_PACK_INDEX_BYTES = 16L shl 20
    private val open = HashMap<String, Handle>()

    init {
        SystemReader.setInstance(ReadOnlySystemReader(SystemReader.getInstance()))
        // Pack data JGit caches lives in the daemon's small heap.
        WindowCacheConfig().apply {
            packedGitLimit = 4L shl 20
            deltaBaseCacheLimit = 2 shl 20
            packedGitOpenFiles = 32
        }.install()
    }

    /** Runs [block] on the repository at [commonDir]; throws when JGit cannot open it (the caller then asks git). */
    fun <T> read(commonDir: String, block: (Repository) -> T): T = Timings.measure(TimedPart.JGIT) {
        val handle = lease(commonDir)
        try {
            block(handle.repository)
        } finally {
            release(handle)
        }
    }

    /** Closes every repository that is not being read; the next read opens it again. */
    @Synchronized
    fun closeIdle() {
        for (handle in open.values.filter { it.users == 0 }) close(handle)
    }

    @Synchronized
    private fun lease(commonDir: String): Handle {
        val handle = open.getOrPut(commonDir) {
            check(packIndexBytes(commonDir) <= MAX_PACK_INDEX_BYTES) { "pack indexes of $commonDir too large for the daemon's heap" }
            Handle(commonDir, FileRepositoryBuilder().setGitDir(File(commonDir)).setMustExist(true).build())
        }
        handle.users++
        return handle
    }

    @Synchronized
    private fun release(handle: Handle) {
        if (--handle.users == 0) handle.timer.touch()
    }

    @Synchronized
    private fun closeIfIdle(handle: Handle) {
        if (handle.users == 0) close(handle)
    }

    // Objects borrowed through `objects/info/alternates` have their indexes loaded too.
    private fun packIndexBytes(commonDir: String): Long {
        val objects = Path.of(commonDir, "objects")
        val alternates = objects.resolve("info/alternates").takeIf { it.isRegularFile() }?.readLines().orEmpty()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { objects.resolve(it).normalize() }
        return (listOf(objects) + alternates).map { it.resolve("pack") }.filter { it.isDirectory() }
            .sumOf { packs -> packs.listDirectoryEntries("*.idx").sumOf { it.fileSize() } }
    }

    private fun close(handle: Handle) {
        if (open[handle.commonDir] !== handle) return
        open.remove(handle.commonDir)
        handle.repository.close()
    }

    private class Handle(val commonDir: String, val repository: Repository) {
        var users = 0
        val timer = IdleTimer(IDLE_MS) { closeIfIdle(this) }
    }
}
