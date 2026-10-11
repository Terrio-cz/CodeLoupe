package codeloupe.lang

import kotlinx.serialization.Serializable

/** A place the parser could not read: 1-based [line] and [col], and what it expected or found ([message]). */
@Serializable
data class SyntaxProblem(val line: Int, val col: Int, val message: String) {
    companion object {
        /** More than this many are of no use to a reader; [FileFacts.errors] still counts them all. */
        const val MAX_KEPT = 10
    }
}
