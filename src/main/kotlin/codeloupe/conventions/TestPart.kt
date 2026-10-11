package codeloupe.conventions

/** How tests are written: file naming, the `@Test` annotation in use, the name style, and the shared helper types test files lean on. */
object TestPart : CardPart {
    private val TEST_ANNOTATION = Regex("^@(?:[\\w.]+\\.)?(?:Test|ParameterizedTest|RepeatedTest)\\b")

    override fun line(context: CardContext, width: Int): String? {
        val tests = context.tests
        if (tests.isEmpty()) return null
        val named = tests.count { it.stem.endsWith("Test") || it.stem.endsWith("Tests") }
        val functions = tests.flatMap { file -> file.facts.decls.filter { it.kind == "fun" && !it.local && it.modifiers.any(TEST_ANNOTATION::containsMatchIn) } }
        val parts = mutableListOf("${tests.size} files ($named named *Test or *Tests)")
        if (functions.isNotEmpty()) parts += "${functions.size} test functions, ${functions.count { it.sig.contains("fun `") }} with backticked names"
        annotation(tests)?.let { parts += it }
        helpers(tests, width)?.let { parts += it }
        return "Tests: ${parts.joinToString("; ")}."
    }

    /** The imports that bring `@Test` in, with the number of files that use each. */
    private fun annotation(tests: List<ModuleFile>): String? {
        val imports = Tally()
        for (file in tests) file.facts.imports.map { it.fqn }.filter { it.endsWith(".Test") }.distinct().forEach { imports.add(it) }
        val top = imports.top(2)
        if (top.isEmpty()) return null
        return "@Test imported from ${top.joinToString(", ") { "${it.key} (${it.count} files)" }}"
    }

    /** Types declared in test sources that are not tests themselves, ranked by how many other test files name them. */
    private fun helpers(tests: List<ModuleFile>, width: Int): String? {
        val declared = HashMap<String, ModuleFile>()
        for (file in tests) for (type in file.types) if (!type.name.endsWith("Test") && !type.name.endsWith("Tests")) declared.putIfAbsent(type.name, file)
        if (declared.isEmpty()) return null
        val users = Tally()
        for (file in tests) {
            val names = file.facts.refs.mapTo(HashSet()) { it.name }
            for ((name, owner) in declared) if (owner !== file && name in names) users.add(name)
        }
        val top = users.top(width, min = MIN)
        if (top.isEmpty()) return null
        return "shared helpers (files using): ${top.joinToString(", ") { "${it.key} ${it.count}" }}"
    }

    private const val MIN = 3
}
