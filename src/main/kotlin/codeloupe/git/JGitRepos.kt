package codeloupe.git

import codeloupe.platform.IdleTimer
import codeloupe.platform.TimedPart
import codeloupe.platform.Timings
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.util.SystemReader
import java.io.File

/**
 * Repositories read in-process with JGit, one per git common dir, opened on first use and closed after [IDLE_MS]
 * unused: an open repository holds its pack files, and Windows refuses to let the user's `git gc` delete a pack
 * that is open.
 */
object JGitRepos {
    private const val IDLE_MS = 10_000L
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
