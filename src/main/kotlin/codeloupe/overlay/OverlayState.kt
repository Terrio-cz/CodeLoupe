package codeloupe.overlay

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Path

/**
 * One worktree's overlay and what the worktree looked like at its last check. Checks of one worktree take turns
 * through [lock]; the fields change only in a check or in the refresh job it started.
 */
internal class OverlayState(val worktree: String, val repoId: String, val file: Path) {
    val lock = Mutex()

    /** `System.nanoTime()` when the last check started; meaningful once [base] is set. */
    var checkedAt = 0L

    /** Set when a refresh failed: the next query checks again whatever [checkedAt] says. */
    @Volatile
    var mustCheck = false

    /** `System.nanoTime()` of the last query: idle worktrees are evicted first. */
    @Volatile
    var usedAt = System.nanoTime()

    /** The base commit [entries] and [scan] are relative to; null until the first check. */
    var base: String? = null

    /** The base commit recorded in [file]; null while there is no file. */
    var fileBase: String? = null

    /** Files in the overlay with the stamp they were read at; [Stamp.MISSING] marks a tombstone. */
    var entries: Map<String, Stamp> = emptyMap()

    /** The worktree at the last check. */
    var scan: Map<String, Stamp> = emptyMap()
    var prune: Set<String> = emptySet()

    /** Files the walk sees but git ignores (an ignored file in a directory that is not ignored as a whole). */
    var ignored: Set<String> = emptySet()

    /** The refresh job that is writing the overlay, if any. */
    var running: Deferred<*>? = null

    /** What queries read now; replaced as a whole whenever the overlay changes, so it can be read without [lock]. */
    @Volatile
    var view: OverlayVersion? = null
}
