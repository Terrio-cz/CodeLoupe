package codeloupe.index

import kotlinx.serialization.Serializable

/** One file to (re)index: its text comes from a git [blob], or from a [file] on disk stamped [mtime] and [size]. */
@Serializable
data class FilePut(val path: String, val blob: String? = null, val file: String? = null, val size: Long = 0, val mtime: Long = 0)
