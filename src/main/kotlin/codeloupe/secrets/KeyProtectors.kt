package codeloupe.secrets

/** Picks how the vault key is protected: the OS key store when there is one, else the passphrase in `CODELOUPE_PASSPHRASE`. */
object KeyProtectors {
    const val PASSPHRASE_VARIABLE = "CODELOUPE_PASSPHRASE"

    /** [wrapped] is the protector name an existing vault was made with, so a vault is always opened the way it was closed. */
    fun choose(env: Map<String, String> = System.getenv(), wrapped: String? = null): KeyProtector {
        val passphrase = env[PASSPHRASE_VARIABLE]?.takeIf { it.isNotEmpty() }
        return when (wrapped) {
            "dpapi" -> DpapiProtector()
            "keychain" -> KeychainProtector()
            "libsecret" -> LibsecretProtector()
            "passphrase" -> PassphraseProtector((passphrase ?: error("this vault is passphrase-protected: set $PASSPHRASE_VARIABLE")).toCharArray())
            null -> when {
                DpapiProtector.available() -> DpapiProtector()
                KeychainProtector.available() -> KeychainProtector()
                LibsecretProtector.available() -> LibsecretProtector()
                passphrase != null -> PassphraseProtector(passphrase.toCharArray())
                else -> error("no OS key store here (Windows DPAPI, macOS Keychain, libsecret): set $PASSPHRASE_VARIABLE to protect the vault with a passphrase")
            }
            else -> error("this vault was made with an unknown key protector '$wrapped'")
        }
    }
}
