package codeloupe.know

/**
 * Reads a file of the brain notation (one fact per line, the first character says what it is) into [FactDraft]s: `!` a blocker, `~` a decision,
 * `+` a fact (a pattern in a file named for patterns, else environment), `=` evidence (environment), `?`, `>`, `->`, `x` and plain lines other.
 * `@` context and `%` state lines belong to one task's moment and are skipped. A trailing `<!-- date time run -->` gives the creation time and the run;
 * the first task id (`TER-167`) is the source task; cited paths become file anchors.
 */
object BrainNotation {
    class Parsed(val facts: List<FactDraft>, val lines: Int, val skipped: Int)

    private val MARKED = Regex("""^(->|!P[0-3]|[+!?>=~xX@%])(?:\s+(.*))?$""")
    private val COMMENT = Regex("""<!--\s*(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})\s*([^\s>]*)\s*-->""")
    private val DATED = Regex("""^\d{4}-\d{2}-\d{2}\b""")
    private val TASK = Regex("""\b([A-Z][A-Z0-9]{1,5}-\d{1,6})\b""")
    private val NOT_TASKS = setOf("UTF", "ISO", "SHA", "CRC", "MD", "RFC", "TLS", "SRID", "JDK")
    private val SKIPPED = setOf("@", "%")
    private const val SUPERSEDED = "SUPERSEDED"

    /**
     * [scope] is the scope of every fact; [rootOf] names the checkout a cited path belongs to (null: the root the facts are sent with);
     * [plusKind] overrides the kind of `+` lines.
     */
    fun parse(text: String, scope: String, plusKind: FactKind? = null, rootOf: (String) -> String? = { null }): Parsed {
        val facts = ArrayList<Pending>()
        var open: Pending? = null
        var lines = 0
        var skipped = 0
        for (raw in text.removePrefix("﻿").lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                open = null
                continue
            }
            lines++
            if (line.startsWith("#")) {
                open = null
                skipped++
                continue
            }
            // An indented line continues the fact above it, whatever it starts with.
            val marked = if (raw.first().isWhitespace()) null else MARKED.matchEntire(line)
            when {
                marked != null && marked.groupValues[1] in SKIPPED -> {
                    open = null
                    skipped++
                }
                marked != null -> {
                    open = Pending(marked.groupValues[1], marked.groupValues[2])
                    facts += open
                }
                open != null -> open.text += " " + line.trim()
                DATED.containsMatchIn(line) -> {
                    open = Pending("", line.trim())
                    facts += open
                }
                else -> skipped++
            }
        }
        return Parsed(facts.mapNotNull { draft(it, scope, plusKind, rootOf) }, lines, skipped)
    }

    private class Pending(val marker: String, var text: String)

    private fun draft(fact: Pending, scope: String, plusKind: FactKind?, rootOf: (String) -> String?): FactDraft? {
        val stamp = COMMENT.find(fact.text)
        val clean = FactText.clean(fact.text.ifBlank { return null }).let { if (fact.marker.startsWith("!P")) "${fact.marker.drop(1)} $it" else it }
        return FactDraft(
            scope = scope, text = clean, kind = kind(fact.marker, plusKind).word,
            task = TASK.findAll(clean).map { it.groupValues[1] }.firstOrNull { it.substringBefore('-') !in NOT_TASKS },
            run = stamp?.groupValues?.get(3)?.ifEmpty { null },
            createdAt = stamp?.let { "${it.groupValues[1]}T${it.groupValues[2]}.000Z" },
            anchors = CitedPaths.of(clean).map { AnchorRef(rootOf(it), it) },
            superseded = SUPERSEDED in clean,
            verify = false,
        )
    }

    private fun kind(marker: String, plusKind: FactKind?): FactKind = when {
        marker.startsWith("!") -> FactKind.BLOCKER
        marker == "~" -> FactKind.DECISION
        marker == "=" -> FactKind.ENVIRONMENT
        marker == "+" -> plusKind ?: FactKind.ENVIRONMENT
        else -> FactKind.OTHER
    }
}
