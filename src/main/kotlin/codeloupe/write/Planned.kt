package codeloupe.write

/** One edit of one file that is ready to be applied, or none when the text is already as asked; [mustDeclare] when the new text has to declare something. */
internal class Planned(val edit: TextEdit?, val mustDeclare: Boolean, val note: String) {
    companion object {
        fun unchanged(note: String) = Planned(null, false, note)
    }
}
