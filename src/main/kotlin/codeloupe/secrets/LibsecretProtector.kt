package codeloupe.secrets

import java.util.Base64

/** Linux libsecret (GNOME Keyring, KWallet) through `secret-tool`, which reads the secret from stdin. */
class LibsecretProtector(private val service: String = "codeloupe-secret-key") : KeyProtector {
    override val name = "libsecret"

    override fun wrap(key: ByteArray): String {
        val account = "k" + System.nanoTime().toString(36) + SecretCrypto.newKey().take(4).joinToString("") { "%02x".format(it) }
        val result = Exec.run(
            listOf("secret-tool", "store", "--label=CodeLoupe vault key", "service", service, "account", account),
            stdin = Base64.getEncoder().encodeToString(key),
        )
        check(result.exit == 0) { "the secret service refused the key (exit ${result.exit})" }
        return account
    }

    override fun unwrap(blob: String): ByteArray {
        val result = Exec.run(listOf("secret-tool", "lookup", "service", service, "account", blob))
        check(result.exit == 0 && result.out.isNotEmpty()) { "the secret service has no key for this vault (exit ${result.exit})" }
        return Base64.getDecoder().decode(result.out)
    }

    fun forget(blob: String) {
        Exec.run(listOf("secret-tool", "clear", "service", service, "account", blob))
    }

    companion object {
        fun available(): Boolean = System.getProperty("os.name").lowercase().contains("linux") && runCatching {
            val protector = LibsecretProtector("codeloupe-probe")
            val blob = protector.wrap(ByteArray(8) { it.toByte() })
            try {
                protector.unwrap(blob).size == 8
            } finally {
                protector.forget(blob)
            }
        }.getOrDefault(false)
    }
}
