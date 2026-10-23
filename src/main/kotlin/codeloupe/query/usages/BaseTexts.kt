package codeloupe.query.usages

import codeloupe.platform.IdleTimer

/**
 * The file texts of one base generation, held while literal lookups come in a burst (a dispatch plan predicts dozens of
 * tasks, each naming its routes and tables) and dropped [IDLE_MS] after the last one. A literal found in the texts in
 * memory costs a few ms; `instr` over the `files` table cost ~40 ms on Terrio, far more once the OS had evicted the
 * file (CL-258). A base whose texts are too large to hold is told once and keeps its lookups in SQL.
 */
internal class BaseTexts {
    class Text(val path: String, val content: String)

    private var held: List<Text>? = null
    private var tooLarge = false
    private val timer = IdleTimer(IDLE_MS) { synchronized(this) { held = null } }

    /** The texts in path order, read by [read] when none are held; null when [read] found them too large. */
    fun get(read: () -> List<Text>?): List<Text>? {
        synchronized(this) {
            if (tooLarge) return null
            held
        }?.let {
            timer.touch()
            return it
        }
        val texts = read()
        synchronized(this) { if (texts == null) tooLarge = true else held = texts }
        timer.touch()
        return texts
    }

    private companion object {
        const val IDLE_MS = 60_000L
    }
}
