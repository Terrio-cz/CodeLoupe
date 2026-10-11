package codeloupe.conventions

/** Where the main files sit (first package level under the shared base) and whether the tests follow the same packages. */
object LayoutPart : CardPart {
    override fun line(context: CardContext, width: Int): String? {
        if (context.main.isEmpty()) return null
        val base = context.basePackage
        val levels = Tally()
        for (file in context.main) levels.add(firstLevel(file.packageName, base))
        val shown = levels.top(width, min = 1)
        val parts = mutableListOf("Layout: main files by package ${shown.joinToString(", ") { "${it.key} ${it.count}" }}")
        if (context.tests.isNotEmpty()) {
            val mainPackages = context.main.mapTo(HashSet()) { it.packageName }
            val mirrored = context.tests.count { it.packageName in mainPackages }
            parts += "$mirrored of ${context.tests.size} test files are in a package that main also has"
        }
        return parts.joinToString("; ") + "."
    }

    private fun firstLevel(packageName: String, base: String): String {
        val rest = packageName.removePrefix(base).removePrefix(".")
        return if (rest.isEmpty()) "(base)" else rest.substringBefore('.')
    }
}
