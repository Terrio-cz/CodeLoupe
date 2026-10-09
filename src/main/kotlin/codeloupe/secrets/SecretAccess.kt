package codeloupe.secrets

import codeloupe.platform.OwnerOnly
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

/** The daemon's handle on the vault: opened on first use (an OS key store probe is not free), and the reason when it cannot be. */
class SecretAccess(private val home: Path, private val env: Map<String, String> = System.getenv(), preset: SecretStore? = null, val rotationDays: Int = 0) {
    private val opened: Result<SecretStore> by lazy { preset?.let { Result.success(it) } ?: runCatching { SecretStore.open(home, env) } }

    val vaultFile: Path = home.resolve("secrets").resolve("vault.env")

    /** The audit of reads and changes; it lives beside the vault and holds no value. */
    val audit = SecretAudit(home.resolve("secrets").resolve("audit.log"))
    private val tokenFile: Path = home.resolve("secrets").resolve("api-token.env")

    val store: SecretStore? get() = opened.getOrNull()

    /** First line of why there is no store (no OS key store and no passphrase), or null. */
    val problem: String? get() = opened.exceptionOrNull()?.message.orEmpty().lineSequence().first().takeIf { opened.isFailure }

    fun vaultExists(): Boolean = Files.isRegularFile(vaultFile)

    /** The token a local MCP server or script presents to `/env/values`; made on first use, readable by this user only. */
    @Synchronized
    fun token(): String {
        // A file that is empty or damaged (a crash while it was made) would otherwise be a token anyone can present.
        runCatching { Files.readString(tokenFile).trim() }.getOrNull()?.takeIf { TOKEN.matches(it) }?.let { return it }
        val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        OwnerOnly.write(tokenFile, token)
        return token
    }

    private companion object {
        val TOKEN = Regex("[0-9a-f]{64}")
    }
}
