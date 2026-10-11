package codeloupe.conventions

import codeloupe.TestRepos
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class ConventionBudgetTest {
    /** Long names everywhere: four suffix families, four base types, four libraries, four thrown types, four helpers. */
    private fun crowdedModule(): Module {
        val root = TestRepos.tmpDir("conventions-budget")
        val main = root.resolve("wide/src/main/kotlin/org/example/wide")
        val test = root.resolve("wide/src/test/kotlin/org/example/wide")
        main.createDirectories()
        test.createDirectories()
        val suffixes = listOf("ExtraordinarilyLongSuffixAlpha", "ExtraordinarilyLongSuffixBeta", "ExtraordinarilyLongSuffixGamma", "ExtraordinarilyLongSuffixDelta")
        for ((s, suffix) in suffixes.withIndex()) for (i in 1..4) {
            val name = "Generated${"X".repeat(8)}${i}Of$suffix"
            val imports = (1..4).joinToString("\n") { "import com.vendor$s.library$it.part.Thing$it" }
            main.resolve("$name.kt").writeText(
                "package org.example.wide.area$s\n\n$imports\n\nclass $name : BaseTypeNumber$s() {\n" +
                    "    fun run() {\n        require(true)\n        throw VeryDescriptiveFailure${suffix}Exception()\n    }\n}\n",
            )
            test.resolve("${name}Test.kt").writeText(
                "package org.example.wide.area$s\n\nimport kotlin.test.Test\nimport org.vendor$s.testing.Thing\n\nclass ${name}Test {\n" +
                    "    private val h = HelperWithAVeryLongNameNumber$s()\n    @Test fun `works`() = Unit\n}\n",
            )
            test.resolve("HelperWithAVeryLongNameNumber$s$i.kt").writeText("package org.example.wide.area$s\n\nclass HelperWithAVeryLongNameNumber$s\n")
        }
        return Module(":wide", root.resolve("wide"))
    }

    @Test
    fun `a crowded module still gets a card of at most 1500 bytes made of whole lines`() {
        val module = crowdedModule()
        val files = ModuleReader.read(module)
        val card = ConventionCard.render(module, files)
        assertTrue(Regex("\\(Generated\\w+\\)").findAll(card).count() < 8, "the lists were shortened to fit: $card")
        assertTrue(card.toByteArray().size <= ConventionCard.MAX_BYTES, "${card.toByteArray().size} bytes\n$card")
        assertTrue(card.lines().all { it.endsWith(".") }, card)
        assertTrue(card.lines().any { it.startsWith("Errors") } && card.lines().any { it.startsWith("Tests") }, "the lines that help most stay: $card")
    }
}
