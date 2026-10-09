package codeloupe.secrets.imports

import codeloupe.JsonFormat
import codeloupe.platform.IsoTime
import codeloupe.secrets.SecretStore
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant

/**
 * The copies of the files an import rewrote, so a rollback can put them back. A copy still holds the values it was made
 * of, so it is sealed with the vault key like a vault entry; the manifest next to it names paths and digests only.
 * `<dir>/<id>/manifest.json` and `<dir>/<id>/<n>.bak`.
 */
class ImportBackups(private val dir: Path, private val store: SecretStore, private val clock: () -> Instant = Instant::now) {
    @Serializable
    class Entry(val path: String, val backup: String, val originalSha256: String, val replacedSha256: String)

    @Serializable
    class Manifest(val id: String, val createdAt: String, val files: List<Entry>)

    @Serializable
    data class Info(val id: String, val createdAt: String, val files: Int)

    @Serializable
    data class RollbackResult(
        val id: String,
        val restored: Int,
        /** Files already as they were (never rewritten, or restored before). */
        val alreadyOriginal: Int,
        /** Files edited after the import; left alone unless the rollback is forced. */
        val changedSince: List<String>,
        /** True when every file is back and the copies are gone. */
        val complete: Boolean,
        /** Files that could not be written back (locked, read-only); the copies stay, run the rollback again. */
        val failed: List<String> = emptyList(),
    )

    /** A backup being made: files are added one by one, each copy on disk before the file is rewritten. */
    inner class Session(val id: String) {
        private val entries = mutableListOf<Entry>()

        fun add(file: Path, original: ByteArray, replacedSha256: String) {
            val name = "${entries.size + 1}.bak"
            Files.createDirectories(dir.resolve(id))
            Files.writeString(dir.resolve(id).resolve(name), store.sealBlob("$id/$name", original))
            entries += Entry(file.toString(), name, ValueFingerprint.sha256(original), replacedSha256)
            writeManifest(Manifest(id, IsoTime.of(clock()), entries.toList()))
        }

        val used: Boolean get() = entries.isNotEmpty()
    }

    fun begin(): Session = Session(IsoTime.of(clock()).replace(Regex("[-:.TZ]"), "").take(14) + "-" + random())

    fun list(): List<Info> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { it.toList() }.mapNotNull(::manifest).map { Info(it.id, it.createdAt, it.files.size) }.sortedByDescending { it.createdAt }
    }

    fun rollback(id: String, force: Boolean = false): RollbackResult {
        val manifest = requireNotNull(manifest(dir.resolve(safe(id)))) { "no import backup named $id" }
        var restored = 0
        var original = 0
        val changed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (entry in manifest.files) {
            val file = Path.of(entry.path)
            val current = runCatching { ValueFingerprint.sha256(Files.readAllBytes(file)) }.getOrNull()
            when {
                current == entry.originalSha256 -> original++
                current == entry.replacedSha256 || force || current == null -> {
                    val bytes = store.openBlob("${manifest.id}/${entry.backup}", Files.readString(dir.resolve(manifest.id).resolve(entry.backup)))
                    try {
                        AtomicFile.write(file, bytes)
                        restored++
                    } catch (e: IOException) {
                        failed += entry.path
                    }
                }
                else -> changed += entry.path
            }
        }
        val complete = changed.isEmpty() && failed.isEmpty()
        if (complete) forget(manifest.id)
        return RollbackResult(manifest.id, restored, original, changed, complete, failed)
    }

    /** Drops the copies without restoring anything. */
    fun forget(id: String): Boolean {
        val folder = dir.resolve(safe(id))
        if (!Files.isDirectory(folder)) return false
        Files.walk(folder).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        return true
    }

    private fun manifest(folder: Path): Manifest? =
        runCatching { JsonFormat.json.decodeFromString(Manifest.serializer(), Files.readString(folder.resolve(MANIFEST))) }.getOrNull()

    private fun writeManifest(manifest: Manifest) =
        Files.writeString(dir.resolve(manifest.id).resolve(MANIFEST), JsonFormat.json.encodeToString(Manifest.serializer(), manifest))

    private fun safe(id: String): String = id.also { require(Regex("[0-9]{14}-[0-9a-f]{6}").matches(it)) { "not a backup id: $id" } }

    private fun random(): String = ByteArray(3).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MANIFEST = "manifest.json"
    }
}
