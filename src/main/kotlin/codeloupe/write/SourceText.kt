package codeloupe.write

import codeloupe.platform.Sha1
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/**
 * A source file as it is on disk: the text decoded as UTF-8 without any change (a byte order mark stays the first character,
 * every line end as it is), so that the bytes written back for an unchanged text are the bytes read.
 */
class SourceText private constructor(val bytes: ByteArray, val text: String) {
    /** The SHA-1 of the bytes: the file did not change while it is the same. */
    val sha: String get() = Sha1.hex(bytes)

    /** The line end most of the file's lines use; `\n` when no line ends in `\r\n` more often than in `\n`. */
    val eol: String
        get() {
            val crlf = Regex("\r\n").findAll(text).count()
            val lf = text.count { it == '\n' } - crlf
            return if (crlf > lf) "\r\n" else "\n"
        }

    companion object {
        /** Null when the file is not there; refused when it is not valid UTF-8, so a file in another encoding is never rewritten. */
        fun read(path: Path): SourceText? {
            if (!Files.isRegularFile(path)) return null
            val bytes = Files.readAllBytes(path)
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                SourceText(bytes, decoder.decode(ByteBuffer.wrap(bytes)).toString())
            } catch (_: CharacterCodingException) {
                throw WriteRefused("$path is not valid UTF-8; edit it by hand")
            }
        }

        fun of(text: String): SourceText = SourceText(text.toByteArray(Charsets.UTF_8), text)
    }
}
