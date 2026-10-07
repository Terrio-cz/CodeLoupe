package codeloupe.index

/** A file as the store records it, next to its facts. */
data class IndexedFile(val path: String, val lang: String, val hash: String, val size: Long, val mtime: Long = 0, val content: String)
