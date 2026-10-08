package codeloupe.secrets

import codeloupe.JsonFormat
import codeloupe.platform.IsoTime
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.Base64

/**
 * The vault: `<home>/secrets/vault.env`, JSON with one encrypted value per (name, scope) and a data key that only the
 * [protector] can open. Listing reads metadata and never touches the key. A value leaves this class only through
 * [resolve] and [knownValues] (callers inject it into a process or mask it), never through a string that is printed.
 * Several processes (the daemon, the CLI) share the file; every call reads it afresh.
 */
class SecretStore(val file: Path, private val protector: KeyProtector, private val clock: () -> Instant = Instant::now) {
    @Serializable
    private class Entry(val meta: SecretMeta, val nonce: String, val value: String)

    @Serializable
    private class Vault(val version: Int = 1, val protector: String, val wrappedKey: String, val entries: List<Entry> = emptyList())

    class Resolved(val meta: SecretMeta, val value: String)

    private var key: ByteArray? = null
    private var cache: Triple<Long, Long, Collection<String>>? = null

    /** What the store holds, narrowest scope last; no value, no key. */
    @Synchronized
    fun list(): List<SecretMeta> = read()?.entries?.map { it.meta }?.sortedWith(compareBy({ it.name }, { SecretScope.parse(it.scope).rank })).orEmpty()

    /** The secrets that apply to [chain] (as [chain] builds it), one per name, the narrowest scope winning; metadata only. */
    @Synchronized
    fun visible(chain: List<SecretScope>): List<SecretMeta> = pick(read()?.entries.orEmpty(), chain).map { it.meta }

    @Synchronized
    fun set(name: String, scope: SecretScope, value: String, source: String = "manual"): SecretMeta {
        require(NAME.matches(name)) { "a name is letters, digits and _, not starting with a digit: '$name'" }
        require(value.isNotEmpty()) { "an empty value is not stored; unset the name instead" }
        val vault = read() ?: newVault()
        val dataKey = key(vault)
        val sealed = SecretCrypto.seal(dataKey, value, aad(name, scope))
        val old = vault.entries.firstOrNull { it.meta.name == name && it.meta.scope == scope.toString() }
        val now = IsoTime.of(clock())
        val meta = SecretMeta(name, scope.toString(), source, old?.meta?.created ?: now, if (old != null) now else null, old?.meta?.lastUsed, old?.meta?.usedBy.orEmpty())
        write(Vault(vault.version, vault.protector, vault.wrappedKey, vault.entries.filterNot { it === old } + Entry(meta, sealed.nonce, sealed.value)))
        return meta
    }

    @Synchronized
    fun remove(name: String, scope: SecretScope): Boolean {
        val vault = read() ?: return false
        val kept = vault.entries.filterNot { it.meta.name == name && it.meta.scope == scope.toString() }
        if (kept.size == vault.entries.size) return false
        write(Vault(vault.version, vault.protector, vault.wrappedKey, kept))
        return true
    }

    /** The values that apply to [chain], by name, and a note of the use on each (`lastUsed`, [usedBy]). */
    @Synchronized
    fun resolve(chain: List<SecretScope>, usedBy: String? = null, names: Set<String>? = null): Map<String, Resolved> {
        val vault = read() ?: return emptyMap()
        val picked = pick(vault.entries, chain).filter { names == null || it.meta.name in names }
        if (picked.isEmpty()) return emptyMap()
        val dataKey = key(vault)
        val values = picked.associate { entry -> entry.meta.name to Resolved(entry.meta, SecretCrypto.open(dataKey, SecretCrypto.Sealed(entry.nonce, entry.value), aad(entry.meta.name, SecretScope.parse(entry.meta.scope)))) }
        if (usedBy != null) touch(vault, picked, usedBy)
        return values
    }

    /** Whether (name, scope) holds exactly [value]; the answer is yes or no, the stored value stays inside. */
    @Synchronized
    fun holds(name: String, scope: SecretScope, value: String): Boolean {
        val vault = read() ?: return false
        val entry = vault.entries.firstOrNull { it.meta.name == name && it.meta.scope == scope.toString() } ?: return false
        return SecretCrypto.open(key(vault), SecretCrypto.Sealed(entry.nonce, entry.value), aad(name, scope)) == value
    }

    /** Encrypts [data] under the vault key, bound to [label]: for a copy of a file that holds values, e.g. an import backup. */
    @Synchronized
    fun sealBlob(label: String, data: ByteArray): String {
        val vault = read() ?: newVault().also { write(it) }
        val sealed = SecretCrypto.seal(key(vault), Base64.getEncoder().encodeToString(data), "blob|$label")
        return "${sealed.nonce}.${sealed.value}"
    }

    @Synchronized
    fun openBlob(label: String, sealed: String): ByteArray {
        val vault = checkNotNull(read()) { "no vault to open the copy with" }
        val plain = SecretCrypto.open(key(vault), SecretCrypto.Sealed(sealed.substringBefore('.'), sealed.substringAfter('.')), "blob|$label")
        return Base64.getDecoder().decode(plain)
    }

    /** Every stored value, for masking text that leaves the process; cached until the vault file changes. */
    @Synchronized
    fun knownValues(): Collection<String> {
        val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() to Files.size(file) }.getOrNull() ?: return emptyList()
        cache?.let { if (it.first == stamp.first && it.second == stamp.second) return it.third }
        val vault = read() ?: return emptyList()
        if (vault.entries.isEmpty()) return emptyList()
        val dataKey = key(vault)
        val all = vault.entries.map { SecretCrypto.open(dataKey, SecretCrypto.Sealed(it.nonce, it.value), aad(it.meta.name, SecretScope.parse(it.meta.scope))) }
        cache = Triple(stamp.first, stamp.second, all)
        return all
    }

    private fun pick(entries: List<Entry>, chain: List<SecretScope>): List<Entry> {
        val allowed = chain.map { it.toString() }.toSet()
        return entries.filter { it.meta.scope in allowed }.groupBy { it.meta.name }
            .map { (_, same) -> same.maxBy { SecretScope.parse(it.meta.scope).rank } }.sortedBy { it.meta.name }
    }

    private fun touch(vault: Vault, used: List<Entry>, usedBy: String) {
        val ids = used.toSet()
        val now = IsoTime.of(clock())
        write(Vault(vault.version, vault.protector, vault.wrappedKey, vault.entries.map { e ->
            if (e !in ids) e else Entry(e.meta.copy(lastUsed = now, usedBy = (listOf(usedBy) + e.meta.usedBy).distinct().take(USED_BY)), e.nonce, e.value)
        }))
    }

    private fun key(vault: Vault): ByteArray = key ?: protector.unwrap(vault.wrappedKey).also { key = it }

    private fun newVault(): Vault {
        val fresh = SecretCrypto.newKey()
        key = fresh
        return Vault(protector = protector.name, wrappedKey = protector.wrap(fresh))
    }

    private fun read(): Vault? {
        if (!Files.isRegularFile(file)) return null
        val vault = JsonFormat.json.decodeFromString(Vault.serializer(), Files.readString(file))
        check(vault.protector == protector.name) { "this vault is protected by ${vault.protector}, not ${protector.name}" }
        return vault
    }

    private fun write(vault: Vault) {
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, "vault", ".tmp")
        try {
            runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
            Files.writeString(temp, JsonFormat.json.encodeToString(Vault.serializer(), vault))
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun aad(name: String, scope: SecretScope) = "$name|$scope"

    companion object {
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private const val USED_BY = 5

        /** The scopes that apply to a caller, widest first: global, then its workspace, then its repository. */
        fun chain(workspace: String? = null, repository: String? = null): List<SecretScope> =
            listOfNotNull(SecretScope.GLOBAL, workspace?.takeIf { it.isNotBlank() }?.let(SecretScope::workspace), repository?.takeIf { it.isNotBlank() }?.let(SecretScope::repository))

        /** The vault of the daemon home [home]; its protector is the one an existing vault was made with, else the best the OS has. */
        fun open(home: Path, env: Map<String, String> = System.getenv()): SecretStore {
            val file = home.resolve("secrets").resolve("vault.env")
            val wrapped = if (Files.isRegularFile(file)) JsonFormat.json.decodeFromString(Vault.serializer(), Files.readString(file)).protector else null
            return SecretStore(file, KeyProtectors.choose(env, wrapped))
        }
    }
}
