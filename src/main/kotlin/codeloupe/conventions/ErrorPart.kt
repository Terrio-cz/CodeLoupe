package codeloupe.conventions

/** How main code fails: what it throws (a type or a factory function), the precondition helpers it calls, what it catches. */
object ErrorPart : CardPart {
    // Only a thrown call counts (an exception type or a factory function); `throw e` is a rethrow and says nothing about style.
    private val THROW = Regex("(?<![\\w.])throw\\s+([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\s*\\(")
    private val CATCH = Regex("(?<![\\w.])catch\\s*\\(\\s*\\w+\\s*:\\s*([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)")
    private val HELPER = Regex("(?<![\\w.])(?<!fun )(require|check|error|requireNotNull|checkNotNull|runCatching)\\s*[({]")

    override fun line(context: CardContext, width: Int): String? {
        val thrown = Tally()
        val caught = Tally()
        val helpers = Tally()
        for (file in context.main) {
            val code = file.code
            THROW.findAll(code).forEach { thrown.add(it.groupValues[1]) }
            CATCH.findAll(code).forEach { caught.add(it.groupValues[1].substringAfterLast('.')) }
            HELPER.findAll(code).forEach { helpers.add(it.groupValues[1]) }
        }
        val parts = listOfNotNull(
            list("throw", thrown, width) { "${it.key} ${it.count}" },
            list("calls", helpers, HELPERS) { "${it.key}() ${it.count}" },
            list("catch", caught, width) { "${it.key} ${it.count}" },
        )
        if (parts.isEmpty()) return null
        return "Errors (main): ${parts.joinToString("; ")}."
    }

    private fun list(label: String, tally: Tally, width: Int, show: (Tally.Entry) -> String): String? {
        val top = tally.top(width)
        return if (top.isEmpty()) null else "$label ${top.joinToString(", ", transform = show)}"
    }

    private const val HELPERS = 4
}
