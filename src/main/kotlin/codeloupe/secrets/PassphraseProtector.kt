package codeloupe.secrets

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The fallback where no OS key store exists: the data key is encrypted with a key derived from a passphrase (PBKDF2-HMAC-SHA256). */
class PassphraseProtector(private val passphrase: CharArray, private val iterations: Int = ITERATIONS) : KeyProtector {
    override val name = "passphrase"
    private val random = SecureRandom()

    override fun wrap(key: ByteArray): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val sealed = SecretCrypto.seal(derive(salt, iterations), Base64.getEncoder().encodeToString(key), AAD)
        return listOf("pbkdf2", iterations, Base64.getEncoder().encodeToString(salt), sealed.nonce, sealed.value).joinToString(":")
    }

    override fun unwrap(blob: String): ByteArray {
        val parts = blob.split(':')
        require(parts.size == 5 && parts[0] == "pbkdf2") { "not a passphrase-wrapped key" }
        val rounds = parts[1].toIntOrNull()
        require(rounds != null && rounds in 1..MAX_ITERATIONS) { "not a passphrase-wrapped key" }
        val key = derive(Base64.getDecoder().decode(parts[2]), rounds)
        val plain = try {
            SecretCrypto.open(key, SecretCrypto.Sealed(parts[3], parts[4]), AAD)
        } catch (e: java.security.GeneralSecurityException) {
            throw IllegalStateException("wrong passphrase for this vault")
        }
        return Base64.getDecoder().decode(plain)
    }

    private fun derive(salt: ByteArray, rounds: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase, salt, rounds, SecretCrypto.KEY_BYTES * 8)).encoded

    companion object {
        /** The cost of a new vault; the count is stored with the key, so a vault made with fewer keeps opening. */
        const val ITERATIONS = 600_000
        private const val MAX_ITERATIONS = 20_000_000
        private const val AAD = "codeloupe-vault-key"
    }
}
