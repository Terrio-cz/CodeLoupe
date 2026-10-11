package codeloupe.conventions

/** How a main file relates to the types it declares: the file is named like its one type, or holds only functions, or several types. */
object FileShapePart : CardPart {
    override fun line(context: CardContext, width: Int): String? {
        val files = context.main
        if (files.isEmpty()) return null
        val single = Tally()
        val renamed = Tally()
        val several = Tally()
        val functions = Tally()
        for (file in files) {
            val types = file.types
            when {
                types.size == 1 && types[0].name == file.stem -> single.add("file", file.stem)
                types.size == 1 -> renamed.add("file", file.stem)
                types.size > 1 -> several.add("file", file.stem)
                file.topLevel.any { it.kind == "fun" || it.kind == "property" } -> functions.add("file", file.stem)
            }
        }
        val parts = listOfNotNull(
            describe(single, "hold one type named like the file"),
            describe(functions, "hold only top-level functions or properties"),
            describe(several, "declare several types"),
            describe(renamed, "hold one type named differently"),
        )
        if (parts.isEmpty()) return null
        return "Files (main, ${files.size}): ${parts.joinToString("; ")}."
    }

    private fun describe(tally: Tally, what: String): String? {
        val top = tally.top(1).firstOrNull() ?: return null
        return "${tally.total} $what (${top.example})"
    }
}
