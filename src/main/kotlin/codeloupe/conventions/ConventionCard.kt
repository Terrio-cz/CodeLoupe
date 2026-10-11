package codeloupe.conventions

/**
 * The conventions of one module as a short text an agent can read before it writes or reviews code: layout, naming, error
 * handling, test style and the libraries in use, each line a count taken from the module's own sources with an example symbol
 * to grep for. Deterministic, no model; capped at [MAX_BYTES]. When the lines do not fit, the lists get shorter first, then
 * the lines that help least are dropped.
 */
object ConventionCard {
    const val MAX_BYTES = 1500

    private val PARTS: List<CardPart> = listOf(
        LayoutPart, FileShapePart, TypeShapePart, SuffixPart, SupertypePart, ErrorPart, TestPart, LibraryPart(tests = false), LibraryPart(tests = true),
    )

    /** Dropped in this order when even one-entry lists do not fit. */
    private val DROP_ORDER: List<CardPart> = listOf(PARTS[8], PARTS[7], PARTS[0], PARTS[4], PARTS[2], PARTS[1])

    private const val WIDEST = 4

    fun render(module: Module, files: List<ModuleFile>): String {
        val context = CardContext(files)
        val header = header(module, context)
        var parts = PARTS
        var dropped = 0
        while (true) {
            for (width in WIDEST downTo 1) {
                val text = assemble(header, parts, context, width)
                if (fits(text)) return text
            }
            if (dropped == DROP_ORDER.size) break
            parts = parts - DROP_ORDER[dropped++]
        }
        return cut(assemble(header, parts, context, 1))
    }

    private fun header(module: Module, context: CardContext): String {
        val base = context.basePackage.takeIf { it.isNotEmpty() }?.let { ", base package $it" }.orEmpty()
        return "Conventions of ${module.name} (counted from its sources): ${context.main.size} main and ${context.tests.size} test files$base."
    }

    private fun assemble(header: String, parts: List<CardPart>, context: CardContext, width: Int): String =
        (listOf(header) + parts.mapNotNull { it.line(context, width) }).joinToString("\n")

    private fun fits(text: String) = text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES

    /** The last resort: whole lines from the top, never a broken one. */
    private fun cut(text: String): String {
        val kept = ArrayList<String>()
        for (line in text.lines()) {
            if (!fits((kept + line).joinToString("\n"))) break
            kept += line
        }
        return kept.joinToString("\n")
    }
}
