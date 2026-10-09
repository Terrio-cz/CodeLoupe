package codeloupe.taskcode

/**
 * Text for a regular expression that a repository wrote: reading past [budgetMs] throws, so a pattern that backtracks
 * without end is cut off instead of holding a thread of the daemon for good.
 */
internal class TimedText(private val text: CharSequence, budgetMs: Long = BUDGET_MS) : CharSequence {
    class Expired : RuntimeException("pattern took too long", null, false, false)

    private val deadline = System.nanoTime() + budgetMs * 1_000_000
    private var reads = 0

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if (++reads and CHECK_EVERY == 0 && System.nanoTime() > deadline) throw Expired()
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

    override fun toString(): String = text.toString()

    companion object {
        const val BUDGET_MS = 100L
        private const val CHECK_EVERY = 0x3FF
    }
}
