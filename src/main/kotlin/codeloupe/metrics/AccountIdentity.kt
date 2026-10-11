package codeloupe.metrics

import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * The account Claude Code itself names in a transcript: a few line types (`bridge-session`, `artifact-autoreact-ledger`) carry the
 * account uuid of the session as `ownerAccountUuid` or `accountUuid`. Only a short fingerprint of it is kept, enough to tell accounts
 * apart and to compare with a uuid the launcher knows, not the uuid itself. Most sessions write no such line, so this is evidence
 * for some sessions, not a stamp for every one.
 */
object AccountIdentity {
    private const val FINGERPRINT_CHARS = 12

    internal fun of(line: JsonObject): String? = (line["ownerAccountUuid"].str() ?: line["accountUuid"].str())?.takeIf { it.isNotBlank() }?.let(::fingerprint)

    fun fingerprint(uuid: String): String =
        MessageDigest.getInstance("SHA-256").digest(uuid.trim().lowercase().toByteArray()).joinToString("") { "%02x".format(it) }.take(FINGERPRINT_CHARS)
}
