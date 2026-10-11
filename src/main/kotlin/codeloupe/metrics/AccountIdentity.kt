package codeloupe.metrics

import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * The account Claude Code itself names in a transcript: a few line types (`bridge-session`, `artifact-autoreact-ledger`) carry the
 * account uuid of the session as `ownerAccountUuid` or `accountUuid`. Only a short fingerprint of it is kept, enough to tell accounts
 * apart and to compare with a uuid the launcher knows, not the uuid itself. Most sessions write no such line, so this is evidence
 * for some sessions, not a stamp for every one; the `SessionStart` hook stamps the rest ([codeloupe.accounts.SessionIdentities]).
 */
object AccountIdentity {
    private const val FINGERPRINT_CHARS = 12

    /** The account id of an identity no listed account claims: `identity-<fingerprint>`. */
    const val ANONYMOUS_PREFIX = "identity-"
    private val FINGERPRINT = Regex("[0-9a-f]{$FINGERPRINT_CHARS}")

    internal fun of(line: JsonObject): String? = (line["ownerAccountUuid"].str() ?: line["accountUuid"].str())?.takeIf { it.isNotBlank() }?.let(::fingerprint)

    fun fingerprint(uuid: String): String =
        MessageDigest.getInstance("SHA-256").digest(uuid.trim().lowercase().toByteArray()).joinToString("") { "%02x".format(it) }.take(FINGERPRINT_CHARS)

    fun anonymousAccount(fingerprint: String): String = ANONYMOUS_PREFIX + fingerprint

    /** A fingerprint as a person may write it (`identity-ab12…` as printed by the export, or the bare 12 hex digits), or null when it is neither. */
    fun parse(raw: String): String? = raw.trim().lowercase().removePrefix(ANONYMOUS_PREFIX).takeIf { FINGERPRINT.matches(it) }
}
