package codeloupe.secrets

/** The one place secret metadata becomes text: names, scope, source and use, never a value, for the tool and the CLI alike. */
object SecretReport {
    fun line(m: SecretMeta): String =
        "${m.name}  ${m.scope}  ${m.source}  created ${day(m.created)}  rotated ${m.rotated?.let(::day) ?: "-"}  used ${m.lastUsed?.let(::day) ?: "never"}" +
            if (m.usedBy.isEmpty()) "" else " by ${m.usedBy.joinToString(", ")}"

    fun lines(metas: List<SecretMeta>): List<String> = metas.map(::line)

    private fun day(time: String) = time.take(10)
}
