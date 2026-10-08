package codeloupe.overlay

/** One entry of a directory listing with the attributes the listing carries; a link is neither a directory nor a regular file. */
internal class DirEntry(val name: String, val directory: Boolean, val regular: Boolean, val mtime: Long, val size: Long)
