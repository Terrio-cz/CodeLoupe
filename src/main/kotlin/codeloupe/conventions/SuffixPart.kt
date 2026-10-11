package codeloupe.conventions

/** The last CamelCase word the top-level type names in main share (`Store`, `Request`): the naming vocabulary of the module. */
object SuffixPart : CardPart {
    private val WORD = Regex("[A-Z]+(?![a-z])|[A-Z]?[a-z0-9]+")

    override fun line(context: CardContext, width: Int): String? {
        val suffixes = Tally()
        for (type in context.main.flatMap { it.types }) {
            val words = WORD.findAll(type.name).map { it.value }.toList()
            if (words.size >= 2) suffixes.add(words.last(), type.name)
        }
        val top = suffixes.top(width, min = MIN)
        if (top.isEmpty()) return null
        return "Type names end in: ${top.joinToString(", ") { "${it.key} ${it.count} (${it.example})" }}."
    }

    private const val MIN = 3
}
