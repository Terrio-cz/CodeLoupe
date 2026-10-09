package codeloupe.compress

/**
 * A javac diagnostic recognised by its shape, `path.java:line: <label>: message`, whatever language the compiler speaks.
 * The label is the localized word for error, warning or note: the common ones are known, and for an unknown one the
 * source line and caret line javac prints right under every diagnostic decide (see [classify]).
 */
object JavacDiagnostic {
    enum class Severity { ERROR, WARNING, NOTE }

    data class Parsed(val path: String, val line: Int, val severity: Severity, val message: String)

    private val SHAPE = Regex("""^(.+?\.java):(\d+): (\p{L}[\p{L}\p{M} ]*?) ?: (.*)$""")
    private val CARET = Regex("""^\s*\^\s*$""")

    // The JDK ships messages in English, German, Japanese and Simplified Chinese; the rest are what localized builds of it
    // and other front ends of javac print (Czech and the Romance, Nordic and Slavic words are written from the locale's
    // vocabulary, not captured).
    private val ERRORS = labels(
        "error", "fehler", "エラー", "错误", "錯誤", "오류", "chyba", "erreur", "errore", "erro", "fout", "fel", "feil", "virhe", "błąd",
        "ошибка", "помилка", "hiba", "hata", "eroare", "σφάλμα", "خطأ", "fatal error", "schwerwiegender fehler",
    )
    private val WARNINGS = labels(
        "warning", "warnung", "警告", "警示", "경고", "varování", "avertissement", "avvertenza", "advertencia", "aviso", "waarschuwing",
        "varning", "advarsel", "varoitus", "ostrzeżenie", "предупреждение", "попередження", "figyelmeztetés", "uyarı", "avertisment",
    )
    private val NOTES = labels(
        "note", "hinweis", "注", "注意", "注释", "注釋", "참고", "poznámka", "remarque", "nota", "opmerking", "anmärkning", "notering",
        "merknad", "huomautus", "uwaga", "примечание", "примітка", "megjegyzés", "not",
    )

    private fun labels(vararg words: String): Set<String> = words.toSet()

    /** The diagnostic [line] is, when its label is one of the known words; null for anything else. */
    fun parse(line: String): Parsed? {
        val m = SHAPE.find(line) ?: return null
        val label = m.groupValues[3].lowercase()
        val severity = when (label) {
            in ERRORS -> Severity.ERROR
            in WARNINGS -> Severity.WARNING
            in NOTES -> Severity.NOTE
            else -> return null
        }
        return Parsed(m.groupValues[1], m.groupValues[2].toInt(), severity, m.groupValues[4])
    }

    /**
     * The diagnostic at [index] of [lines]. A label that is no known word counts as an error when the second line below is a
     * caret line, which javac prints under each diagnostic; an unknown warning is thereby kept as an error line, which is the
     * safer mistake for a build summary.
     */
    fun classify(lines: List<String>, index: Int): Parsed? {
        parse(lines[index])?.let { return it }
        val m = SHAPE.find(lines[index]) ?: return null
        if (lines.getOrNull(index + 2)?.let(CARET::matches) != true) return null
        return Parsed(m.groupValues[1], m.groupValues[2].toInt(), Severity.ERROR, m.groupValues[4])
    }

    fun isError(line: String): Boolean = parse(line)?.severity == Severity.ERROR
}
