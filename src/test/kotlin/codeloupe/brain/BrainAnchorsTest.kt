package codeloupe.brain

import codeloupe.brain.BrainWorld.Companion.BILLING
import codeloupe.brain.BrainWorld.Companion.CHECKOUT
import codeloupe.brain.BrainWorld.Companion.entry
import kotlinx.coroutines.runBlocking
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `file:line` becomes the stable id of the enclosing declaration; the index follows renames and moves and reports what is gone. */
class BrainAnchorsTest {
    private val world = BrainWorld()

    @AfterTest
    fun cleanUp() = world.close()

    private fun resolve(vararg refs: LineRef, trusted: Boolean = false) =
        runBlocking { world.service.resolve(ResolveRequest(world.workspace.toString(), codeRoot = world.repo.toString(), trusted = trusted, refs = refs.toList())) }.refs

    @Test
    fun `a line in a writer's own worktree is anchored to its enclosing member with module, file and a name an overload cannot confuse`() {
        world.export(emptyList())
        val total = BILLING.let { path -> world.repo.resolve(path).toFile().readLines().indexOfFirst { "fun total" in it } + 1 }
        val tax = world.repo.resolve(BILLING).toFile().readLines().indexOfFirst { "fun tax(amount: Long)" in it } + 1
        val (one, two) = resolve(LineRef(BILLING, total, "slow"), LineRef(BILLING, tax, "wrong rounding"), trusted = true)
        assertEquals(listOf("@sym:com.example.billing.Billing.total", "@file:$BILLING", "@mod:billing"), one.anchors)
        assertEquals("@sym:com.example.billing.Billing.tax(Long)", two.anchors.first(), "tax is overloaded: the parameter types name the one meant")
    }

    @Test
    fun `an untrusted line gets the symbol only when the finding names it, because lines drift`() {
        world.export(emptyList())
        val total = world.repo.resolve(BILLING).toFile().readLines().indexOfFirst { "fun total" in it } + 1
        val (named, unnamed) = resolve(LineRef(BILLING, total, "Billing.total adds tax twice"), LineRef(BILLING, total, "rounding is wrong here"))
        assertTrue(named.anchors.first().startsWith("@sym:"), named.anchors.toString())
        assertEquals(listOf("@file:$BILLING", "@mod:billing"), unnamed.anchors)
    }

    @Test
    fun `where the text names only the type the anchor is the type, a plain word anchors nothing, and a sentence-named test is never an anchor`() {
        world.export(emptyList())
        val lines = world.repo.resolve(BILLING).toFile().readLines()
        val charge = lines.indexOfFirst { "fun charge(" in it } + 1
        val sentence = lines.indexOfFirst { "charges once per order" in it } + 1
        val (type, word, test) = resolve(
            LineRef(BILLING, charge, "PaymentGateway drops the currency"), LineRef(BILLING, charge, "the charge is wrong, charge it again"), LineRef(BILLING, sentence, "PaymentGateway test is vacuous"),
        )
        val (trusted) = resolve(LineRef(BILLING, sentence, "anything"), trusted = true)
        assertEquals("@sym:com.example.billing.PaymentGateway", type.anchors.first(), "the line says where in the type, the text says it is about the type")
        assertEquals(listOf("@file:$BILLING", "@mod:billing"), word.anchors, "charge is a plain word here")
        assertEquals("@sym:com.example.billing.PaymentGateway", test.anchors.first())
        assertEquals("@sym:com.example.billing.PaymentGateway", trusted.anchors.first(), "in the writer's worktree the sentence-named test still anchors on its type: an anchor is one word")
    }

    @Test
    fun `an elided path, a file the index does not hold and an unknown file`() {
        world.export(emptyList())
        world.repo.resolve("docs").toFile().mkdirs()
        world.repo.resolve("docs/notes.md").writeText("notes\n")
        val (elided, doc, none) = resolve(LineRef("billing/.../Billing.kt", 3), LineRef("docs/notes.md", 1), LineRef("nowhere/Else.kt", 9))
        assertTrue("@file:$BILLING" in elided.anchors)
        assertEquals(listOf("@file:docs/notes.md"), doc.anchors)
        assertTrue(none.anchors.isEmpty())
    }

    @Test
    fun `a renamed or moved symbol is followed, a removed one is dangling, and an entry's anchors are checked once`() {
        world.export(
            listOf(
                entry("f-kept0001", "total stays", anchors = listOf("@sym:com.example.billing.Billing.total", "@file:$BILLING")),
                entry("f-gone0001", "legacy rounding", anchors = listOf("@sym:com.example.billing.Billing.legacy")),
                entry("f-move0001", "pay moved packages", anchors = listOf("@sym:com.example.old.Checkout.pay", "@file:ui/src/main/kotlin/com/example/old/Checkout.kt")),
                entry("f-nofile01", "script is gone", anchors = listOf("@file:run/deleted.mjs")),
            ),
        )
        val report = runBlocking { world.service.hygiene(world.workspace.toString()) }
        assertEquals(setOf("f-gone0001", "f-nofile01"), report.dangling.map { it.id }.toSet())
        assertEquals(
            setOf("f-move0001|@sym:com.example.old.Checkout.pay|@sym:com.example.ui.Checkout.pay", "f-move0001|@file:ui/src/main/kotlin/com/example/old/Checkout.kt|@file:$CHECKOUT"),
            report.moved.map { "${it.id}|${it.from}|${it.to}" }.toSet(),
        )
        assertEquals(6, report.checkedAnchors)
    }

    @Test
    fun `near-identical entries are proposed for a merge, but TER-167 and TER-168 are two entries and a superseding entry settles the pair`() {
        world.export(
            listOf(
                entry("f-dupa0001", "! pre-routing plugin that can throw must install AFTER configureCors or error 400 responses lack the CORS header"),
                entry("f-dupb0001", "! pre-routing plugin that can throw must install AFTER configureCors or error 400 responses lack the CORS header here"),
                entry("f-num00001", "! the TER-167 importer must run once per day before the slice"),
                entry("f-num00002", "! the TER-168 importer must run once per day before the slice"),
                entry("f-old00001", "! reload the cache after the import finishes and before the slice"),
                entry("f-new00001", "! reload the cache after the import finishes and before the slice", anchors = emptyList()).copy(supersedes = listOf("f-old00001")),
            ),
        )
        val pairs = runBlocking { world.service.hygiene(world.workspace.toString()) }.duplicates
        assertEquals(listOf(setOf("f-dupa0001", "f-dupb0001")), pairs.map { setOf(it.a, it.b) })
    }

    @Test
    fun `the backfill gives file and module to a file-and-line entry and the symbol only where its text names it, and follows a move`() {
        val total = world.repo.resolve(BILLING).toFile().readLines().indexOfFirst { "fun total" in it } + 1
        world.export(
            listOf(
                entry("f-named001", "Billing.total adds the tax twice", files = listOf("$BILLING:$total")),
                entry("f-quiet001", "rounding is wrong here", files = listOf("$BILLING:$total")),
                entry("f-away0001", "pay moved packages", anchors = listOf("@sym:com.example.old.Checkout.pay")),
                entry("f-text0001", "no file at all"),
            ),
        )
        val result = runBlocking { world.service.backfill(world.workspace.toString()) }
        assertEquals(BackfillResult(anchored = 2, moved = 1, skipped = 0), result)
        val commands = world.engine.calls.filter { it.firstOrNull() == "batch" }.flatMap { it.drop(1) }
        assertEquals(
            setOf(
                "anchor f-named001 @sym:com.example.billing.Billing.total --who codeloupe",
                "anchor f-named001 @file:$BILLING --who codeloupe",
                "anchor f-named001 @mod:billing --who codeloupe",
                "anchor f-quiet001 @file:$BILLING --who codeloupe",
                "anchor f-quiet001 @mod:billing --who codeloupe",
                "anchor f-away0001 @sym:com.example.ui.Checkout.pay --replaces @sym:com.example.old.Checkout.pay --who codeloupe",
            ),
            commands.toSet(),
        )
    }
}
