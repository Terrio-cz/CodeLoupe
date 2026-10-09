package codeloupe.ingest

import codeloupe.platform.Sha1
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * A fingerprint of the last bytes before an offset of a transcript. Resuming at an offset is only right if the file still has the
 * same bytes before it; a transcript rewritten to the same or a larger size passes the size check, but not this one.
 */
internal object TailHash {
    private const val TAIL_BYTES = 64

    /** The hash of the bytes just before [offset], null for offset 0 or a file that cannot be read. */
    fun at(file: Path, offset: Long): String? {
        if (offset <= 0) return null
        return try {
            Files.newByteChannel(file).use { channel ->
                val from = maxOf(0L, offset - TAIL_BYTES)
                val buffer = ByteBuffer.allocate((offset - from).toInt())
                channel.position(from)
                while (buffer.hasRemaining()) if (channel.read(buffer) < 0) return null
                Sha1.hex(buffer.array())
            }
        } catch (_: IOException) {
            null
        }
    }

    /** True unless [state] recorded a fingerprint that the file no longer has; a row from before fingerprints, or an unreadable file, is taken as it is. */
    fun intact(file: Path, state: FileState): Boolean {
        val recorded = state.tail ?: return true
        val now = at(file, state.offset) ?: return true
        return now == recorded
    }
}
