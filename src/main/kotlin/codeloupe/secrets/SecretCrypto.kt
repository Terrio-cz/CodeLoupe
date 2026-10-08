package codeloupe.secrets

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM. The name and scope are authenticated with the value, so a ciphertext cannot be moved under another name. */
object SecretCrypto {
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    class Sealed(val nonce: String, val value: String)

    fun seal(key: ByteArray, plain: String, aad: String): Sealed {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad.toByteArray())
        return Sealed(b64.encodeToString(nonce), b64.encodeToString(cipher.doFinal(plain.toByteArray())))
    }

    fun open(key: ByteArray, sealed: Sealed, aad: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, unb64.decode(sealed.nonce)))
        cipher.updateAAD(aad.toByteArray())
        return String(cipher.doFinal(unb64.decode(sealed.value)))
    }
}
