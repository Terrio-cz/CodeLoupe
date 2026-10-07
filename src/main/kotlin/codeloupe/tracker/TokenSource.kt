package codeloupe.tracker

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a tracker token comes from, read in-process on every request (a dotenv file can be rotated without a
 * restart). The value never leaves the daemon — not in `toString`, errors or logs.
 */
sealed interface TokenSource {
    fun read(): String

    class Env(val name: String, private val env: () -> Map<String, String> = System::getenv) : TokenSource {
        override fun read(): String = checked(env()[name]?.trim()?.takeIf { it.isNotEmpty() } ?: throw TrackerException("token variable $name is not set"))

        override fun toString() = "env $name"
    }

    /** A `KEY=value` line of a dotenv-style file. */
    class DotEnv(val file: Path, val key: String) : TokenSource {
        override fun read(): String {
            val text = runCatching { Files.readString(file) }.getOrElse { throw TrackerException("token file $file is not readable") }
            return checked(text.lineSequence().firstNotNullOfOrNull(::value) ?: throw TrackerException("token file $file has no $key"))
        }

        private fun value(line: String): String? {
            val trimmed = line.trim().removePrefix("export ").trim()
            if (!trimmed.startsWith("$key=")) return null
            return trimmed.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'").takeIf { it.isNotEmpty() }
        }

        override fun toString() = "dotenv $file $key"
    }

    companion object {
        /** The JDK quotes an invalid header value in its exception; such a token is refused before it gets that far. */
        fun checked(token: String): String =
            token.takeIf { t -> t.all { it in '!'..'~' } } ?: throw TrackerException("the token has characters not allowed in an HTTP header")
    }
}
