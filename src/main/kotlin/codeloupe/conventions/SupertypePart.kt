package codeloupe.conventions

/** The supertypes most top-level types in main extend or implement: the base classes and interfaces a new type is expected to use. */
object SupertypePart : CardPart {
    override fun line(context: CardContext, width: Int): String? {
        val supertypes = Tally()
        for (type in context.main.flatMap { it.types }) type.supertypes.distinct().forEach { supertypes.add(it, type.name) }
        val top = supertypes.top(width, min = MIN)
        if (top.isEmpty()) return null
        return "Types extend or implement: ${top.joinToString(", ") { "${it.key} ${it.count} (${it.example})" }}."
    }

    private const val MIN = 3
}
