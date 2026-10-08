package codeloupe.secrets

/** Protects the store's data key so that the file alone is worth nothing: the OS key store, or a passphrase. */
interface KeyProtector {
    val name: String

    /** Wraps [key]; the blob is stored in the vault file. */
    fun wrap(key: ByteArray): String

    /** The key a [wrap] blob holds; throws when this user (or passphrase) cannot open it. */
    fun unwrap(blob: String): ByteArray
}
