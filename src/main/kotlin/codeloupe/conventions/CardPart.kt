package codeloupe.conventions

/** One line of a card. [width] is how many entries a ranked list may show; a part with nothing true to say returns null. */
interface CardPart {
    fun line(context: CardContext, width: Int): String?
}
