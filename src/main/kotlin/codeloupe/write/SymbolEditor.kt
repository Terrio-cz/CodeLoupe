package codeloupe.write

import codeloupe.lang.DeclFact
import codeloupe.lang.FileFacts

/**
 * Edits of single declarations of one file, as text edits on the file's own text: only the declaration's range changes, so
 * everything else keeps its bytes. The facts must be those of [text] itself; offsets and line ends come from them.
 */
internal class SymbolEditor(private val text: String, private val eol: String, private val facts: FileFacts) {
    /** The declaration, with its documentation and annotations, becomes [code]; [code] equal to it changes nothing. */
    fun replace(index: Int, code: String): Planned {
        val d = checked(index, lineWise = false)
        // `symbol` shows an enum constant with the comma or semicolon of its line; they are not part of it.
        val given = if (d.kind == ENUM_ENTRY) code.trimEnd().trimEnd(',', ';') else code
        val block = Reindent.block(given, Reindent.indentAt(text, d.startOffset), eol)
        if (block.replace("\r\n", "\n") == text.substring(d.startOffset, d.endOffset).replace("\r\n", "\n")) return Planned.unchanged("already as given")
        return Planned(TextEdit(d.startOffset, d.endOffset, block), mustDeclare = true, note = "replaced")
    }

    fun insertAfter(index: Int, code: String): Planned {
        val d = checked(index, lineWise = true)
        if (d.kind == ENUM_ENTRY) throw WriteRefused("${d.name} is an enum constant: add members to the enum with insert_member")
        val indent = Reindent.indentAt(text, d.startOffset)
        val at = lineEnd(d.endOffset)
        return Planned(TextEdit(at, at, after(d, indent, code)), mustDeclare = true, note = "inserted after")
    }

    fun insertBefore(index: Int, code: String): Planned {
        val d = checked(index, lineWise = true)
        if (d.kind == ENUM_ENTRY) throw WriteRefused("${d.name} is an enum constant: add members to the enum with insert_member")
        val indent = Reindent.indentAt(text, d.startOffset)
        val gap = if (tight(d, code)) eol else eol + eol
        return Planned(TextEdit(d.startOffset, d.startOffset, Reindent.block(code, indent, eol) + gap + indent), mustDeclare = true, note = "inserted before")
    }

    /** The declaration, its lines, and one blank line beside them go. */
    fun delete(index: Int): Planned {
        val d = checked(index, lineWise = true)
        if (d.kind == ENUM_ENTRY) throw WriteRefused("${d.name} is an enum constant: edit the enum by hand")
        val start = lineStart(d.startOffset)
        val lineEnd = lineEnd(d.endOffset)
        val end = if (lineEnd < text.length) afterBreak(lineEnd) else lineEnd
        val before = previousLine(start)
        val next = if (end < text.length) nextLine(end) else null
        val (from, to) = when {
            before != null && text.substring(before, start).isBlank() -> before to end
            next != null && text.substring(end, next).isBlank() -> start to next
            else -> start to end
        }
        return Planned(TextEdit(from, to, ""), mustDeclare = false, note = "deleted")
    }

    /** [code] becomes a member of the type at [typeIndex]: at its `start`, its `end` or `after_properties`. */
    fun insertMember(typeIndex: Int, code: String, position: String): Planned {
        val type = facts.decls[typeIndex]
        if (type.kind !in TYPES) throw WriteRefused("${type.name} is not a type that has members")
        val typeIndent = Reindent.indentAt(text, type.startOffset)
        if (type.bodyOpen < 0) {
            if (type.kind == "annotation") throw WriteRefused("${type.name} has no body")
            val inner = typeIndent + unit(typeIndent)
            return Planned(TextEdit(type.endOffset, type.endOffset, " {" + eol + inner + Reindent.block(code, inner, eol) + eol + typeIndent + "}"), true, "inserted into")
        }
        val members = members(typeIndex)
        val entries = facts.decls.filter { it.parent == typeIndex && it.kind == ENUM_ENTRY }
        if (members.isEmpty()) return insertIntoEmpty(type, typeIndent, entries, code)
        val first = members.first()
        return when (position) {
            "start" -> insertBefore(facts.decls.indexOf(first), code)
            "after_properties" -> {
                val anchor = members.lastOrNull { it.kind == "property" }
                if (anchor == null) insertBefore(facts.decls.indexOf(first), code) else insertAfter(facts.decls.indexOf(anchor), code)
            }
            else -> insertAfter(facts.decls.indexOf(members.last()), code)
        }
    }

    // A body with no member: only whitespace, only comments, or an enum's constants.
    private fun insertIntoEmpty(type: DeclFact, typeIndent: String, entries: List<DeclFact>, code: String): Planned {
        val inner = typeIndent + unit(typeIndent)
        val block = Reindent.block(code, inner, eol)
        if (entries.isNotEmpty()) {
            val last = entries.last().endOffset
            val terminator = terminatorAfter(last)
            if (terminator != null) return Planned(TextEdit(terminator, terminator, eol + eol + inner + block), true, "inserted into")
            // The constants end with a comma: the semicolon goes on a line of its own after it.
            val comma = trailingComma(last)
            return if (comma != null) Planned(TextEdit(comma, comma, eol + inner + ";" + eol + eol + inner + block), true, "inserted into")
            else Planned(TextEdit(last, last, ";" + eol + eol + inner + block), true, "inserted into")
        }
        val interior = text.substring(type.bodyOpen + 1, type.bodyClose)
        if (interior.isBlank()) return Planned(TextEdit(type.bodyOpen + 1, type.bodyClose, eol + inner + block + eol + typeIndent), true, "inserted into")
        if (!Reindent.startsLine(text, type.bodyClose)) throw WriteRefused("the closing brace of ${type.name} shares its line: edit by hand")
        val at = lineStart(type.bodyClose)
        return Planned(TextEdit(at, at, inner + block + eol), true, "inserted into")
    }

    private fun members(typeIndex: Int): List<DeclFact> {
        val type = facts.decls[typeIndex]
        return facts.decls.filter { it.parent == typeIndex && !it.local && it.startOffset > type.bodyOpen && it.kind != ENUM_ENTRY }
    }

    // The offset after a comma that follows the last constant of an enum, when there is one.
    private fun trailingComma(offset: Int): Int? {
        var i = offset
        while (i < text.length && text[i].isWhitespace()) i++
        return if (i < text.length && text[i] == ',') i + 1 else null
    }

    // The `;` that ends an enum's constants, when it comes before anything else.
    private fun terminatorAfter(offset: Int): Int? {
        var i = offset
        while (i < text.length && (text[i].isWhitespace() || text[i] == ',')) i++
        return if (i < text.length && text[i] == ';') i + 1 else null
    }

    private fun after(d: DeclFact, indent: String, code: String): String {
        val gap = if (tight(d, code)) eol else eol + eol
        return gap + indent + Reindent.block(code, indent, eol)
    }

    // Single-line properties follow each other without a blank line.
    private fun tight(d: DeclFact, code: String): Boolean =
        d.kind in TIGHT && d.start == d.end && Reindent.trimmed(code).let { it.size == 1 && looksLikeProperty(it[0]) }

    private fun looksLikeProperty(line: String): Boolean = KOTLIN_PROPERTY.containsMatchIn(line) || JAVA_FIELD.matches(line.trim())

    /** The declaration at [index] if it can be edited as a unit: not local, not a parameter, not part of `int a, b;`; [lineWise] also needs its own lines. */
    private fun checked(index: Int, lineWise: Boolean): DeclFact {
        val d = facts.decls[index]
        if (d.local) throw WriteRefused("${d.name} is local to code: replace the declaration that holds it")
        val parent = facts.decls.getOrNull(d.parent)
        if (parent != null && (parent.bodyOpen < 0 || d.startOffset < parent.bodyOpen) && d.kind == "property") {
            throw WriteRefused("${d.name} is a parameter of ${parent.name}: replace the declaration of ${parent.name}")
        }
        if (d.kind == "property" && (neighbour(d.endOffset, forward = true) == ',' || neighbour(d.startOffset, forward = false) == ',')) {
            throw WriteRefused("${d.name} is declared together with others (`a, b`): edit the line by hand")
        }
        if (!Reindent.startsLine(text, d.startOffset)) throw WriteRefused("${d.name} shares its line with other code: edit by hand")
        if (lineWise && !tailIsQuiet(d.endOffset)) throw WriteRefused("${d.name} is followed by other code on its last line: edit by hand")
        return d
    }

    private fun neighbour(offset: Int, forward: Boolean): Char? {
        var i = if (forward) offset else offset - 1
        while (i in text.indices && text[i].isWhitespace()) i += if (forward) 1 else -1
        return text.getOrNull(i)
    }

    // Only whitespace or a line comment follows on the line.
    private fun tailIsQuiet(offset: Int): Boolean {
        val rest = text.substring(offset, lineEnd(offset)).trim()
        return rest.isEmpty() || rest.startsWith("//")
    }

    private fun lineStart(offset: Int): Int = text.lastIndexOf('\n', offset - 1) + 1

    /** The offset of the line break that ends the line holding [offset] (the end of the text without one). */
    private fun lineEnd(offset: Int): Int {
        val newline = text.indexOf('\n', offset)
        return when {
            newline < 0 -> text.length
            newline > 0 && text[newline - 1] == '\r' -> newline - 1
            else -> newline
        }
    }

    private fun afterBreak(lineEnd: Int): Int = if (text.startsWith("\r\n", lineEnd)) lineEnd + 2 else lineEnd + 1

    private fun previousLine(start: Int): Int? = if (start == 0) null else lineStart(start - 1)

    private fun nextLine(start: Int): Int? = if (start >= text.length) null else afterBreak(lineEnd(start)).coerceAtMost(text.length)

    private fun unit(indent: String) = if (indent.contains('\t')) "\t" else "    "

    private companion object {
        const val ENUM_ENTRY = "enum_entry"
        val TYPES = setOf("class", "interface", "object", "enum", "companion", "annotation")
        val TIGHT = setOf("property", "typealias")
        val KOTLIN_PROPERTY = Regex("""^\s*((public|private|protected|internal|override|open|abstract|final|lateinit|const|@\w+(\([^)]*\))?)\s+)*(val|var)\b""")
        val JAVA_FIELD = Regex("""[^(=;]*(=.*)?;""")
    }
}
