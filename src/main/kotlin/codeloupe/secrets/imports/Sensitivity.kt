package codeloupe.secrets.imports

/** Whether a variable looks like a credential, by its name or by the shape of its value; settings such as a token limit are not. */
object Sensitivity {
    private val NAME = Regex("(?i)(token|secret|passw(or)?d|pwd|api[_-]?key|apikey|private|credential|auth|bearer|dsn|cert|signing|access[_-]?key|(^|_)key($|_)|connection[_-]?string|database[_-]?url|redis[_-]?url|webhook)")
    private val SHAPES = listOf(
        Regex("^(sk|pk|rk)[-_][A-Za-z0-9_-]{16,}"), Regex("^gh[pousr]_[A-Za-z0-9]{20,}"), Regex("^xox[abprs]-"), Regex("^eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\."),
        Regex("^AKIA[0-9A-Z]{12,}"), Regex("^perm[:-]"), Regex("://[^/\\s:@]+:[^/\\s@]+@"), Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    )

    fun of(name: String, value: String): Boolean = NAME.containsMatchIn(name) || SHAPES.any { it.containsMatchIn(value) }
}
