package codeloupe.lang

/** One language: which files it owns and how a file becomes [FileFacts]. */
interface LanguageAdapter {
    val lang: String

    /** Must not load the parser: the daemon asks this for every path without ever parsing. */
    fun owns(path: String): Boolean

    fun extract(path: String, text: String): FileFacts
}
