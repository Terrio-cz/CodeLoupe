package codeloupe.hooks

import java.nio.file.Files
import java.nio.file.Path

/**
 * The transcript a hook or `GET /session-weight` names, checked before it is opened: an absolute local path to a regular `.jsonl` file.
 * A network path (`\\server\share`, `//server/share`) is refused without being touched, because opening it makes Windows try to sign in
 * to that server; anything else that is not a transcript answers the same as a missing file.
 */
object TranscriptPath {
    private const val MAX_LENGTH = 4096

    fun of(raw: String): Path? {
        if (raw.length > MAX_LENGTH || raw.any { it.isISOControl() }) return null
        val slashed = raw.replace('\\', '/')
        if (slashed.startsWith("//") || !slashed.endsWith(".jsonl")) return null
        val path = runCatching { Path.of(raw) }.getOrNull()?.takeIf { it.isAbsolute } ?: return null
        return path.takeIf { runCatching { Files.isRegularFile(path) }.getOrDefault(false) }
    }
}
