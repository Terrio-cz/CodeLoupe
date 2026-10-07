package codeloupe.overlay

/** What a file looked like on disk: modification time (µs) and size. A changed stamp means "read it again". */
data class Stamp(val mtime: Long, val size: Long) {
    companion object {
        /** The file is not there: a tombstone in the overlay. */
        val MISSING = Stamp(0, 0)
    }
}
