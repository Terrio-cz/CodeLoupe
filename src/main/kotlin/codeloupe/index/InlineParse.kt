package codeloupe.index

/**
 * Which updates the daemon parses itself. More files, or one large file (generated code), go to a build worker:
 * its heap goes away with it, while the daemon's small heap must never run out.
 */
object InlineParse {
    const val MAX_FILES = 200
    const val MAX_FILE_BYTES = 512L * 1024

    fun fits(puts: List<FilePut>): Boolean = puts.size <= MAX_FILES && puts.all { it.size <= MAX_FILE_BYTES }
}
