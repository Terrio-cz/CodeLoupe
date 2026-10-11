package codeloupe.conventions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConventionCardTest {
    private val root = ConventionFixtures.repo()
    private val core = ModuleFinder.find(root).first { it.name == ":core" }
    private val card = ConventionCards.card(core)
    private val lines = card.lines()

    @Test
    fun `each line states a count taken from the sources with an example symbol`() {
        assertEquals(
            listOf(
                "Conventions of :core (counted from its sources): 7 main and 4 test files, base package com.example.core.",
                "Layout: main files by package store 3, http 2, model 2; 4 of 4 test files are in a package that main also has.",
                "Files (main, 7): 5 hold one type named like the file (ItemStore); 1 hold only top-level functions or properties (Helpers); 1 declare several types (Kinds).",
                "Types (main, 7 top-level): class 3, data class 1, enum 1, object 1, sealed 1; 1 internal, KDoc on 3 of 6 public.",
                "Type names end in: Store 3 (ItemStore).",
                "Types extend or implement: AutoCloseable 3 (ItemStore).",
                "Errors (main): throw StoreException 3; calls check() 3, require() 1; catch IllegalStateException 3.",
                "Tests: 4 files (3 named *Test or *Tests); 6 test functions, 3 with backticked names; @Test imported from kotlin.test.Test (3 files); shared helpers (files using): StoreFixtures 3.",
                "Main imports (files): kotlinx.serialization 3, org.slf4j 3.",
                "Test imports (files): kotlin.test 3.",
            ),
            lines,
        )
    }

    @Test
    fun `a literal or a comment never lends a word to the card`() {
        assertFalse("marker-literal" in card)
        assertFalse("marker-resource" in card)
        assertFalse("throw IllegalStateException" in card, "the throw in the string and in the comment are not counted")
        assertTrue("throw StoreException 3;" in card)
    }

    @Test
    fun `the same sources give the same card`() {
        assertEquals(card, ConventionCards.card(core))
    }

    @Test
    fun `build output, resources and other modules are not part of the module`() {
        val files = ModuleReader.read(core).map { it.path }
        assertTrue(files.none { "Generated" in it || "application.conf" in it || "Main.kt" in it }, files.toString())
        assertEquals(setOf(":core", ":tools:cli"), ModuleFinder.find(root).map { it.name }.toSet())
    }

    @Test
    fun `a module with no tests still gets a card without test lines`() {
        val files = ModuleReader.read(core).filter { !it.test }
        val text = ConventionCard.render(core, files)
        assertTrue(text.startsWith("Conventions of :core (counted from its sources): 7 main and 0 test files"), text)
        assertFalse(text.lines().any { it.startsWith("Tests:") || it.startsWith("Test imports") })
    }

    @Test
    fun `the paths of a task map to the nearest module that owns them`() {
        val owners = ConventionCards.owning(root, listOf("core/src/main/kotlin/com/example/core/store/UserStore.kt", "tools/cli/src/main/kotlin/Main.kt", "README.md"))
        assertEquals(listOf(":core", ":tools:cli"), owners.map { it.name })
        assertEquals(listOf(":core"), ConventionCards.owning(root, listOf("core/src/test/kotlin/x/Y.kt", "core/src/main/kotlin/x/Z.kt")).map { it.name })
        assertEquals(1, ConventionCards.owning(root, owners.map { "${root.relativize(it.dir)}/src/main/kotlin/A.kt" }, limit = 1).size)
    }

    @Test
    fun `modules are ranked by main source lines`() {
        val ranked = ConventionCards.largest(root)
        assertEquals(listOf(":core", ":tools:cli"), ranked.map { it.first.name })
        assertTrue(ranked[0].second > ranked[1].second)
    }
}
