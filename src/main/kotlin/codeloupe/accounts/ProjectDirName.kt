package codeloupe.accounts

/** The directory name Claude Code gives the transcripts of a working directory: every character that is not a letter or digit becomes `-`. */
object ProjectDirName {
    fun of(root: String): String = root.trimEnd('/', '\\').map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }.joinToString("")
}
