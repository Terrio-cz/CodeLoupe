package codeloupe.doc

import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads a text file as a [Doc]: a plan, a brain note, a persisted tool output. Only files under the caller's own root
 * or under [roots] (where the agent harness keeps its outputs and plans) are read, links resolved first, and never a
 * file that looks like a secret store: the daemon is a local reader of text, not a way to any file of the machine.
 */
class DocFiles(private val roots: List<Path>) {
    fun load(root: String, path: String): Doc {
        val given = path.trim()
        require(given.isNotEmpty()) { "pass path: a text file, relative to root or absolute" }
        val base = root.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val file = runCatching { Path.of(given) }.getOrNull() ?: throw IllegalArgumentException("not a path: $given")
        val resolved = if (file.isAbsolute) file else base?.resolve(file) ?: throw IllegalArgumentException("pass an absolute path, or root for a relative one")
        val real = runCatching { resolved.toRealPath() }.getOrNull() ?: throw IllegalArgumentException("no file $given")
        val allowed = (listOfNotNull(base) + roots).mapNotNull { runCatching { it.toRealPath() }.getOrNull() }
        require(allowed.any { real.startsWith(it) }) { "$given is outside root and the allowed folders (${roots.joinToString(", ") { it.toString().replace('\\', '/') }})" }
        require(Files.isRegularFile(real)) { "$given is not a file" }
        require(!SECRET.containsMatchIn(real.fileName.toString()) && !SECRET_DIR.containsMatchIn(real.toString().replace('\\', '/'))) { "${real.fileName} looks like a secret store; not read" }
        require(Files.size(real) <= MAX_BYTES) { "$given is over ${MAX_BYTES / 1_000_000} MB; read a window with a tool that streams" }
        val bytes = Files.readAllBytes(real)
        require(bytes.take(PROBE).none { it == 0.toByte() }) { "$given is binary" }
        return Doc.parse(display(real, base), String(bytes, Charsets.UTF_8))
    }

    /** The path relative to root when under it, else absolute; forward slashes either way. */
    private fun display(real: Path, base: Path?): String {
        val under = base?.let { runCatching { it.toRealPath() }.getOrNull() }?.takeIf { real.startsWith(it) }
        return (if (under != null) under.relativize(real) else real).toString().replace('\\', '/')
    }

    companion object {
        const val MAX_BYTES = 8_000_000L
        private const val PROBE = 4_096
        private val SECRET = Regex("(?i)^\\.env(\\.|$)|\\.(pem|key|pfx|p12|kdbx|keystore)$|^id_(rsa|ed25519|ecdsa)|credentials|secrets?\\.")

        private val SECRET_DIR = Regex("(?i)/(\\.ssh|\\.aws|\\.gnupg|\\.kube|\\.azure)/")

        /** What a daemon on this machine may read besides the caller's root: the agent harness's own folder and its temporary outputs. */
        fun defaultRoots(): List<Path> = listOf(
            Path.of(System.getProperty("user.home"), ".claude"),
            Path.of(System.getProperty("java.io.tmpdir"), "claude"),
        )
    }
}
