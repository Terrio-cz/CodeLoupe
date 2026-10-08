package codeloupe.secrets

import java.util.Base64

/**
 * macOS Keychain: the data key is a generic password of this user's keychain, under [service] and an account of its own.
 * The wrap blob only names the account. `security` takes the password as an argument, so it is visible to this user's
 * processes for the instant of the call; the same user could read the keychain item anyway.
 */
class KeychainProtector(private val service: String = "codeloupe-secret-key") : KeyProtector {
    override val name = "keychain"

    override fun wrap(key: ByteArray): String {
        val account = "k" + System.nanoTime().toString(36) + SecretCrypto.newKey().take(4).joinToString("") { "%02x".format(it) }
        val result = Exec.run(listOf("security", "add-generic-password", "-a", account, "-s", service, "-w", Base64.getEncoder().encodeToString(key), "-U"))
        check(result.exit == 0) { "the keychain refused the key (exit ${result.exit})" }
        return account
    }

    override fun unwrap(blob: String): ByteArray {
        val result = Exec.run(listOf("security", "find-generic-password", "-a", blob, "-s", service, "-w"))
        check(result.exit == 0 && result.out.isNotEmpty()) { "the keychain has no key for this vault (exit ${result.exit})" }
        return Base64.getDecoder().decode(result.out)
    }

    /** Removes the item of [blob]; for tests and for deleting a vault. */
    fun forget(blob: String) {
        Exec.run(listOf("security", "delete-generic-password", "-a", blob, "-s", service))
    }

    companion object {
        fun available(): Boolean = System.getProperty("os.name").lowercase().contains("mac") && runCatching {
            val protector = KeychainProtector("codeloupe-probe")
            val blob = protector.wrap(ByteArray(8) { it.toByte() })
            try {
                protector.unwrap(blob).size == 8
            } finally {
                protector.forget(blob)
            }
        }.getOrDefault(false)
    }
}
