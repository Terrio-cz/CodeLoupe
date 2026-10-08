package codeloupe.secrets

import java.time.Instant

/** The one place secret metadata becomes text: names, scope, source and use, never a value, for the tool and the CLI alike. */
object SecretReport {
    /** [rotationDays] > 0 adds `ROTATE` to a secret that has been as it is for that long. */
    fun line(m: SecretMeta, rotationDays: Int = 0, now: Instant = Instant.now()): String =
        "${m.name}  ${m.scope}  ${m.source}  created ${day(m.created)}  rotated ${m.rotated?.let(::day) ?: "-"}  used ${m.lastUsed?.let(::day) ?: "never"}" +
            (if (m.usedBy.isEmpty()) "" else " by ${m.usedBy.joinToString(", ")}") +
            (if (SecretAge.due(m, rotationDays, now)) "  ROTATE: ${SecretAge.days(m, now)} days old (limit $rotationDays)" else "")

    fun lines(metas: List<SecretMeta>, rotationDays: Int = 0, now: Instant = Instant.now()): List<String> = metas.map { line(it, rotationDays, now) }

    fun audit(e: SecretAudit.Event): String = "${e.at}  ${e.action.name.lowercase()}  ${e.name}  ${e.scope}  ${e.consumer}"

    private fun day(time: String) = time.take(10)
}
