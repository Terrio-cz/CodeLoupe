package codeloupe.jobs

import codeloupe.JsonFormat
import codeloupe.platform.OwnerOnly
import codeloupe.secrets.KeyProtector
import codeloupe.secrets.SecretCrypto
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Seals the [JobSpec]s of queued and running jobs, which hold command lines and `--env` values as given: AES-256-GCM under a
 * random key kept in `<home>/jobs.key`, wrapped by the OS key store like the secret vault's (DPAPI, Keychain, libsecret, or
 * `CODELOUPE_PASSPHRASE`). Where there is none, nothing is stored and such jobs are lost on a restart, as before.
 * [protector] gets the protector name the key file was made with, or null for a new key.
 */
class JobSpecKey(private val file: Path, private val protector: (wrapped: String?) -> KeyProtector) {
    @Serializable
    private class Stored(val protector: String, val wrapped: String)

    private var key: ByteArray? = null

    /** Why there is no key, once getting one failed; asked again only by a new daemon. */
    @Volatile
    var problem: String? = null
        private set

    fun seal(id: String, spec: JobSpec): String? {
        val key = key() ?: return null
        val sealed = SecretCrypto.seal(key, JsonFormat.json.encodeToString(JobSpec.serializer(), spec), aad(id))
        return sealed.nonce + "." + sealed.value
    }

    /** The spec [text] holds, or null when this key cannot open it (another key, a damaged row, no key). */
    fun open(id: String, text: String): JobSpec? {
        val key = key() ?: return null
        val (nonce, value) = text.split('.', limit = 2).takeIf { it.size == 2 } ?: return null
        return runCatching { JsonFormat.json.decodeFromString(JobSpec.serializer(), SecretCrypto.open(key, SecretCrypto.Sealed(nonce, value), aad(id))) }.getOrNull()
    }

    @Synchronized
    fun key(): ByteArray? {
        key?.let { return it }
        if (problem != null) return null
        return runCatching { load() }.onFailure { problem = it.message ?: it.toString() }.getOrNull()?.also { key = it }
    }

    private fun load(): ByteArray {
        if (Files.isRegularFile(file)) {
            val stored = JsonFormat.json.decodeFromString(Stored.serializer(), Files.readString(file))
            return protector(stored.protector).unwrap(stored.wrapped)
        }
        val fresh = SecretCrypto.newKey()
        val chosen = protector(null)
        if (!OwnerOnly.create(file, JsonFormat.json.encodeToString(Stored.serializer(), Stored(chosen.name, chosen.wrap(fresh))))) return load()
        return fresh
    }

    private fun aad(id: String) = "job-spec:$id"
}
