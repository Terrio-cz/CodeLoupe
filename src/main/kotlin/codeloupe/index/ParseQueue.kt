package codeloupe.index

import codeloupe.lang.FileFacts

/**
 * Writes of a store that wait for their files to be parsed together: [Extraction.extractAll] parses a batch at once (on several
 * threads in a worker), and every step runs in the order it was queued, so the store ends up as parsing one file at a time left it.
 * A batch holds at most [maxChars] of text and [maxSteps] steps, and a larger file goes alone: the parser never holds more than one
 * large file at once, which is what a worker's heap is sized for (CL-125). The limit is lower than that for the daemon's sake: a reply
 * of 128 K chars of Kotlin is ~1 MB of JSON and dies young, while 512 K of text gave replies that went straight to the old
 * generation and grew the daemon's committed heap by ~150 MB over a 300-file refresh (CL-247).
 */
internal class ParseQueue(private val maxChars: Long = MAX_CHARS, private val maxSteps: Int = MAX_STEPS) {
    private class Step(val file: ParseFile?, val write: (FileFacts?) -> Unit)

    private val steps = ArrayList<Step>()
    private var chars = 0L

    /** Parses [file] and hands its facts to [write], after every step queued before it. */
    fun parse(file: ParseFile, write: (FileFacts) -> Unit) {
        makeRoom(file.text.length)
        steps += Step(file) { write(it!!) }
        chars += file.text.length
    }

    /** Runs [step] after every step queued before it: at once when nothing waits. [size] is the text it holds until then. */
    fun then(size: Int = 0, step: () -> Unit) {
        makeRoom(size)
        if (steps.isEmpty()) {
            step()
            return
        }
        steps += Step(null) { step() }
        chars += size
    }

    /** Parses what waits and runs every queued step. A step that queues more has that run before the next step. */
    fun flush() {
        if (steps.isEmpty()) return
        val batch = steps.toList()
        steps.clear()
        chars = 0
        val facts = Extraction.extractAll(batch.mapNotNull { it.file }).iterator()
        for (step in batch) {
            step.write(if (step.file != null) facts.next() else null)
            flush()
        }
    }

    private fun makeRoom(size: Int) {
        if (steps.isNotEmpty() && (chars + size > maxChars || steps.size >= maxSteps)) flush()
    }

    private companion object {
        const val MAX_STEPS = 64
        const val MAX_CHARS = 128L * 1024
    }
}
