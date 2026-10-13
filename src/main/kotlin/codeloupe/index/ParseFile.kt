package codeloupe.index

import kotlinx.serialization.Serializable

/** One file to parse: its path (for the language and the name of a script) and its text. */
@Serializable
data class ParseFile(val path: String, val text: String)
