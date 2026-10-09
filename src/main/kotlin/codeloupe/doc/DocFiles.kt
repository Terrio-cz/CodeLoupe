package codeloupe.doc

import codeloupe.events.Scrubber
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads a text file as a [Doc]: a plan, a brain note, a persisted tool output. Only files under the caller's own root
 * or under [roots] (where the agent harness keeps its outputs and plans) are read, links resolved first, and never a
 * file that looks like a secret store: the daemon is a local reader of text, not a way to any file of the machine.
 * [withheld] are folders never read whatever root asks for them (the daemon's own home: its vault and its API token).
 */
class DocFiles(private val roots: List<Path>, private val withheld: List<Path> = emptyList()) {
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
        require(withheld.mapNotNull { runCatching { it.toRealPath() }.getOrNull() }.none { real.startsWith(it) }) { "${real.fileName} is in the daemon's own folder; not read" }
        require(!SECRET.containsMatchIn(real.fileName.toString()) && !SECRET_DIR.containsMatchIn(real.toString().replace('\\', '/'))) { "${real.fileName} looks like a secret store; not read" }
        require(Files.size(real) <= MAX_BYTES) { "$given is over ${MAX_BYTES / 1_000_000} MB; read a window with a tool that streams" }
        val bytes = Files.readAllBytes(real)
        require(bytes.take(PROBE).none { it == 0.toByte() }) { "$given is binary" }
        return Doc.parse(display(real, base), String(bytes, Charsets.UTF_8))
    }

    /** The output of a job, by its handle: read as it is on disk, with secrets masked, its newest [MAX_BYTES] when larger. */
    fun loadLog(handle: String, log: Path): Doc {
        require(Files.isRegularFile(log)) { "no output kept for $handle" }
        val size = Files.size(log)
        val text = Files.newInputStream(log).use { input ->
            if (size > MAX_BYTES) input.skipNBytes(size - MAX_BYTES)
            String(input.readAllBytes(), Charsets.UTF_8)
        }
        return Doc.parse(handle, Scrubber.text(text))
    }

    /** The path relative to root when under it, else absolute; forward slashes either way. */
    private fun display(real: Path, base: Path?): String {
        val under = base?.let { runCatching { it.toRealPath() }.getOrNull() }?.takeIf { real.startsWith(it) }
        return (if (under != null) under.relativize(real) else real).toString().replace('\\', '/')
    }

    companion object {
        const val MAX_BYTES = 8_000_000L
        private const val PROBE = 4_096
        private val SECRET = Regex("(?i)^\\.env(\\.|$)|\\.(pem|key|pfx|p12|kdbx|keystore)$|^id_(rsa|ed25519|ecdsa)|credentials|secrets?\\.|^\\.(npmrc|netrc|git-credentials|pgpass|pypirc|htpasswd|dockercfg)$")

        private val SECRET_DIR = Regex("(?i)/(\\.ssh|\\.aws|\\.gnupg|\\.kube|\\.azure|\\.docker|\\.config/gh|\\.config/gcloud)/")

        /** What a daemon on this machine may read besides the caller's root: the agent harness's own folder and its temporary outputs. */
        fun defaultRoots(): List<Path> = listOf(
            Path.of(System.getProperty("user.home"), ".claude"),
            Path.of(System.getProperty("java.io.tmpdir"), "claude"),
        )
    }
}
