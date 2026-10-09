package codeloupe.secrets

import java.util.Base64

/**
 * macOS Keychain: the data key is a generic password of this user's keychain, under [service] and an account of its own.
 * The wrap blob only names the account. The key reaches `security` on its standard input (`security -i` reads commands from there), so it
 * is never part of a command line that other users' `ps` lists; `unwrap` reads it back from the tool's output.
 */
class KeychainProtector internal constructor(private val service: String, private val exec: (List<String>, String?) -> Exec.Result) : KeyProtector {
    constructor(service: String = "codeloupe-secret-key") : this(service, { command, stdin -> Exec.run(command, stdin) })

    override val name = "keychain"

    init {
        require(SAFE.matches(service)) { "keychain service '$service' has characters a security command cannot take plain" }
    }

    override fun wrap(key: ByteArray): String {
        val account = "k" + System.nanoTime().toString(36) + SecretCrypto.newKey().take(4).joinToString("") { "%02x".format(it) }
        val secret = Base64.getEncoder().encodeToString(key)
        require(SAFE.matches(account) && SAFE.matches(secret))
        val add = exec(listOf("security", "-i"), "add-generic-password -a $account -s $service -w $secret -U\n")
        // The interactive mode has its own exit status: ask the keychain whether the item is there.
        check(add.exit == 0 && exec(listOf("security", "find-generic-password", "-a", account, "-s", service), null).exit == 0) { "the keychain refused the key (exit ${add.exit})" }
        return account
    }

    override fun unwrap(blob: String): ByteArray {
        val result = exec(listOf("security", "find-generic-password", "-a", blob, "-s", service, "-w"), null)
        check(result.exit == 0 && result.out.isNotEmpty()) { "the keychain has no key for this vault (exit ${result.exit})" }
        return Base64.getDecoder().decode(result.out)
    }

    /** Removes the item of [blob]; for tests and for deleting a vault. */
    fun forget(blob: String) {
        exec(listOf("security", "delete-generic-password", "-a", blob, "-s", service), null)
    }

    companion object {
        /** What a plain (unquoted) word of a `security -i` command may hold; base64 and the names made here fit. */
        private val SAFE = Regex("[A-Za-z0-9._+/=-]+")

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
