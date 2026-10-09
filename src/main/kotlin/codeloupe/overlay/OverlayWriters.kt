package codeloupe.overlay

import codeloupe.index.Store
import codeloupe.platform.IdleTimer
import java.nio.file.Path
import java.sql.Connection

/**
 * The write connection of each overlay store, kept for [idleMs] after a refresh. Opening a store nobody else has open
 * costs 5-9 ms on Windows (the write-ahead log and its index are created, and removed again on close), against under 1 ms
 * while any connection holds it: the next edit of a worktree being worked on skips that. A kept connection gives its page
 * cache back after each use, and every one is closed when it has been idle that long, before its file is deleted
 * ([drop]) and with the overlays ([close]); nothing is kept while nobody edits.
 */
internal class OverlayWriters(idleMs: Long) : AutoCloseable {
    private val parked = HashMap<Path, Connection>()
    private val timer = IdleTimer(idleMs, ::closeParked)

    /** Runs [block] on the write connection of [file]: the parked one, or a new one. A connection that failed is closed, not kept. */
    fun <T> use(file: Path, block: (Connection) -> T): T {
        val db = synchronized(this) { parked.remove(file) } ?: Store.open(file)
        val result = try {
            block(db)
        } catch (e: Throwable) {
            runCatching { db.close() }
            throw e
        }
        if (runCatching { db.createStatement().use { it.execute("PRAGMA shrink_memory") } }.isSuccess) park(file, db) else runCatching { db.close() }
        return result
    }

    /** Closes the parked connection of [file], so that nothing keeps it open. */
    fun drop(file: Path) {
        synchronized(this) { parked.remove(file) }?.let { runCatching { it.close() } }
    }

    /** How many connections are parked (tests). */
    fun parkedCount(): Int = synchronized(this) { parked.size }

    override fun close() = closeParked()

    private fun park(file: Path, db: Connection) {
        synchronized(this) { parked.put(file, db) }?.let { runCatching { it.close() } }
        timer.touch()
    }

    private fun closeParked() {
        val closing = synchronized(this) { parked.values.toList().also { parked.clear() } }
        closing.forEach { runCatching { it.close() } }
    }
}
