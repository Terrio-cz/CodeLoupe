package codeloupe.secrets

import java.time.Duration
import java.time.Instant

/** How long a secret has been as it is: since it was rotated, else since it was created. */
object SecretAge {
    fun days(meta: SecretMeta, now: Instant): Long = Duration.between(Instant.parse(meta.rotated ?: meta.created), now).toDays().coerceAtLeast(0)

    /** Past [maxDays]; 0 turns the reminder off. */
    fun due(meta: SecretMeta, maxDays: Int, now: Instant): Boolean = maxDays > 0 && days(meta, now) >= maxDays
}
