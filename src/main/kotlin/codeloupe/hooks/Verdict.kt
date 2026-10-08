package codeloupe.hooks

/** What the steering decision made of one call: advice, or the reason it is left alone (counted in `/status` and the replay). */
sealed interface Verdict {
    /** [anchor] is a path inside the repository the advice is about: the file read, the first place searched, the working directory. */
    data class Advise(val advice: Advice, val anchor: String) : Verdict

    data class Skip(val reason: String) : Verdict {
        /** How far the call got towards advice; of several commands in one line the furthest one gives the reason. */
        val rank: Int get() = ORDER.indexOf(reason)
    }

    companion object {
        /** Not a search or read of code. */
        const val NOT_A_SEARCH = "not-a-search"

        /** A search or read of documents, configuration or other files. */
        const val OTHER_FILES = "other-files"

        /** The daemon does not know the repository or the file. */
        const val NOT_INDEXED = "not-indexed"

        /** A read of part of a file, or of a short one. */
        const val SMALL = "small"

        private val ORDER = listOf(NOT_A_SEARCH, OTHER_FILES, NOT_INDEXED, SMALL)
    }
}
