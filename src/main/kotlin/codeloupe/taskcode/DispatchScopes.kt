package codeloupe.taskcode

/** The [DispatchScope] of a request's `root`, or null when the root is plain code and owns no tracker project. */
fun interface DispatchScopes {
    fun of(root: String): DispatchScope?

    companion object {
        val NONE = DispatchScopes { null }
    }
}
