package codeloupe.jobs

/** Where `job move` puts a waiting job in its slot's queue. */
sealed interface QueuePosition {
    data object First : QueuePosition

    data class Before(val id: String) : QueuePosition

    data class After(val id: String) : QueuePosition

    companion object {
        /** Exactly one of the three, as the CLI, the HTTP API and the MCP tool take them. */
        fun of(first: Boolean, before: String?, after: String?): QueuePosition {
            val given = listOfNotNull(First.takeIf { first }, before?.let(::Before), after?.let(::After))
            require(given.size == 1) { "say where: exactly one of first, before <id>, after <id>" }
            return given.single()
        }
    }
}
