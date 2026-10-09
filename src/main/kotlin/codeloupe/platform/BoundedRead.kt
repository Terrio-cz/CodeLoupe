package codeloupe.platform

import java.nio.file.Files
import java.nio.file.Path

/** Reads a small file that somebody else's program writes (a `.meta.json` beside a transcript), and refuses one that is not small. */
object BoundedRead {
    const val SMALL = 64 * 1024L

    /** The text of [file], or null when it is missing, unreadable or longer than [max] bytes. */
    fun text(file: Path, max: Long = SMALL): String? = runCatching {
        if (Files.size(file) > max) null else Files.readString(file)
    }.getOrNull()
}
