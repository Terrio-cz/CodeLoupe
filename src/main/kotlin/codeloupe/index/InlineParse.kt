package codeloupe.index

/**
 * Which updates the daemon parses itself. More files, or one large file (generated code), go to a build worker:
 * its heap goes away with it, while the daemon's small heap must never run out.
 */
object InlineParse {
    const val MAX_FILES = 200
    const val MAX_FILE_BYTES = 512L * 1024

    /**
     * Files of an update a query waits for (a worktree's overlay, a merge-base) that the daemon hands to its parse worker
     * itself. The daemon holds one [ParseQueue] batch of text at a time whatever the count, and a warm worker parses a
     * file in a few milliseconds where a build worker first starts a JVM at lowered priority (CL-247).
     */
    const val MAX_FILES_WITH_WORKER = 1000

    fun fits(puts: List<FilePut>, maxFiles: Int = MAX_FILES): Boolean = puts.size <= maxFiles && puts.all { it.size <= MAX_FILE_BYTES }

    /** [fits] for an update a query waits for: more files when they are parsed outside the daemon's heap. */
    fun fitsAwaited(puts: List<FilePut>): Boolean = fits(puts, if (Extraction.usesWorker) MAX_FILES_WITH_WORKER else MAX_FILES)
}
