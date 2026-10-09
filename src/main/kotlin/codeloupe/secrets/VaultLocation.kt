package codeloupe.secrets

import java.nio.file.Path

/** Where the vault lives matters to its owner: the daemon's home is a cache folder on macOS and Linux, and cleaning tools empty those. */
object VaultLocation {
    /**
     * A warning for the person who is about to create a vault at [vault], or null. On macOS the home is under `~/Library/Caches`, on Linux
     * under `$XDG_CACHE_HOME` (`~/.cache`): a cache cleaner that empties it deletes the vault and leaves its keychain item behind, and
     * the values are gone. Windows keeps the vault in `%LOCALAPPDATA%`, which cleaners leave alone.
     */
    fun cacheWarning(
        vault: Path,
        env: Map<String, String> = System.getenv(),
        os: String = System.getProperty("os.name"),
        userHome: Path = Path.of(System.getProperty("user.home")),
    ): String? {
        val name = os.lowercase()
        val cache = when {
            name.startsWith("mac") -> userHome.resolve("Library").resolve("Caches")
            name.startsWith("windows") -> return null
            else -> env["XDG_CACHE_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: userHome.resolve(".cache")
        }
        if (!vault.toAbsolutePath().normalize().startsWith(cache.toAbsolutePath().normalize())) return null
        return "note: the vault is in $vault, inside the cache folder $cache. A cache cleaner that empties it deletes the stored values. " +
            "Keep a copy of that folder, or set CODELOUPE_HOME to a folder outside the cache before storing more."
    }
}
