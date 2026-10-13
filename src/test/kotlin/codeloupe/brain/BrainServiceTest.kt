package codeloupe.brain

import codeloupe.brain.BrainWorld.Companion.BILLING
import codeloupe.brain.BrainWorld.Companion.entry
import codeloupe.brain.BrainWorld.Companion.finding
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The daemon's ranking over a fixture code repository and a canned brain: code proximity above text, in the order the card asks for. */
class BrainServiceTest {
    private val world = BrainWorld()

    @AfterTest
    fun cleanUp() = world.close()

    private fun rank(files: List<String>, terms: List<String> = emptyList(), vararg sections: BrainSection): Map<String, BrainRankSection> =
        runBlocking { world.service.rank(BrainRankRequest(world.workspace.toString(), task = "TER-9", files = files, terms = terms, sections = sections.toList().ifEmpty { listOf(BrainSection(tasks = true)) })) }.sections

    private fun ids(section: BrainRankSection) = section.hits.map { it.id ?: it.where }

    @Test
    fun `findings recorded on the changed files and symbols come before callers, the module, the task link and text-only matches`() {
        world.export(
            entries = listOf(
                entry("f-text0001", "billing total rounding is a mystery", task = "TER-7", files = emptyList()),
                entry("f-task0001", "payment provider needs idempotency keys", task = "TER-1", files = listOf("tax/.../Rates.kt")),
                entry("f-mod00001", "billing module tests need a fixed clock", task = "TER-7", anchors = listOf("@mod:billing")),
                entry("f-call0001", "checkout must not call total twice", task = "TER-7", files = listOf("ui/src/main/kotlin/com/example/ui/Checkout.kt:5")),
                entry("f-file0001", "total adds tax before rounding", task = "TER-5", files = listOf("billing/.../Billing.kt:3")),
                entry("f-symb0001", "tax with a rate is integer division", task = "TER-5", anchors = listOf("@sym:com.example.billing.Billing.tax(Int,Int)")),
            ),
            findings = listOf(finding("TER-8", "!P2 Billing.total drops the remainder (Billing.kt:3)", files = listOf("Billing.kt:3"), symbols = listOf("Billing.total"))),
        )
        val hits = rank(listOf(BILLING), listOf("billing total"))
        val order = ids(hits.getValue("facts"))
        // The first four are tied to the file or its symbols; then the caller, the module, the task link, and last the text-only entry.
        assertEquals(setOf("f-file0001", "f-symb0001", "tasks/TER-8.md:1"), order.take(3).toSet(), order.toString())
        assertEquals(listOf("f-call0001", "f-mod00001", "f-task0001", "f-text0001"), order.drop(3), order.toString())
        val byId = hits.getValue("facts").hits.associateBy { it.id ?: it.where }
        assertContains(byId.getValue("f-file0001").why, "file")
        assertContains(byId.getValue("f-symb0001").why, "symbol")
        assertContains(byId.getValue("f-call0001").why, "caller")
        assertContains(byId.getValue("f-task0001").why, "task")
        assertEquals(listOf("text"), byId.getValue("f-text0001").why)
    }

    @Test
    fun `without a file the order is the text's, and the entries of the asking task are left out`() {
        world.export(entries = listOf(entry("f-aaaaaaa1", "billing total rounding", task = "TER-7"), entry("f-bbbbbbb1", "billing total rounding mystery notes", task = "TER-9"), entry("f-ccccccc1", "unrelated gradle daemon note")))
        val facts = rank(emptyList(), listOf("billing", "total", "rounding")).getValue("facts")
        assertEquals(listOf("f-aaaaaaa1"), ids(facts), "TER-9 asks, so what TER-9 wrote itself is not an earlier finding")
    }

    @Test
    fun `an entry that predicted a finding rises within its tier and one that was shown without being needed keeps its place`() {
        world.export(
            entries = listOf(
                entry("f-plain001", "total adds tax before rounding", files = listOf("billing/.../Billing.kt")),
                entry("f-asked001", "total adds tax before rounding twice", files = listOf("billing/.../Billing.kt")),
                entry("f-risen001", "total adds tax before rounding thrice", files = listOf("billing/.../Billing.kt")),
            ),
            feedback = mapOf("f-asked001" to BrainFeedback(shown = 5, hit = 0), "f-risen001" to BrainFeedback(shown = 2, hit = 2)),
        )
        val order = ids(rank(listOf(BILLING), listOf("total", "tax", "rounding")).getValue("facts"))
        assertEquals("f-risen001", order.first())
        assertTrue("feedback" in rank(listOf(BILLING), listOf("total")).getValue("facts").hits.first { it.id == "f-risen001" }.why)
        val plain = rank(listOf(BILLING), emptyList()).getValue("facts").hits.associateBy { it.id }
        assertTrue(plain.getValue("f-asked001").score!! >= plain.getValue("f-plain001").score!! - 0, "never shown less than an untouched entry of the same link")
    }

    @Test
    fun `sections filter by topic, level, kind and superseded, and anchoredOnly keeps the entries tied to the files themselves`() {
        world.export(
            entries = listOf(
                entry("f-keep0001", "total adds tax before rounding", topic = "review-patterns", files = listOf("billing/.../Billing.kt")),
                entry("f-other001", "total adds tax before rounding elsewhere", topic = "db", files = listOf("billing/.../Billing.kt")),
                entry("f-gone0001", "total adds tax old", topic = "review-patterns", files = listOf("billing/.../Billing.kt"), superseded = true),
                entry("f-decide01", "~ keep the integer division -- measured", topic = "decisions", kind = "decision", anchors = listOf("@sym:com.example.billing.Billing.tax(Long)")),
                entry("f-mod00001", "module only note", anchors = listOf("@mod:billing")),
                entry("f-machine1", "billing total machine note", level = "machine", topic = "docker"),
            ),
            findings = listOf(finding("TER-8", "!P2 Billing.total drops the remainder (Billing.kt:3)", files = listOf("Billing.kt:3"), symbols = listOf("Billing.total"))),
        )
        val sections = rank(
            listOf(BILLING), listOf("billing total"),
            BrainSection("facts", scopes = listOf("review-patterns", "db"), levels = listOf("workspace")),
            BrainSection("withTasks", scopes = listOf("review-patterns"), tasks = true),
            BrainSection("decisions", kinds = listOf("decision"), anchoredOnly = true, levels = listOf("workspace")),
            BrainSection("anchored", anchoredOnly = true, tasks = true, levels = listOf("workspace")),
            BrainSection("machine", levels = listOf("machine")),
        )
        assertEquals(setOf("f-keep0001", "f-other001", "f-mod00001"), ids(sections.getValue("facts")).toSet(), "scoped topics, no superseded, no machine, no task lines")
        assertTrue("tasks/TER-8.md:1" in ids(sections.getValue("withTasks")))
        assertEquals(listOf("f-decide01"), ids(sections.getValue("decisions")), "a decision anchored on a symbol of the file")
        assertFalse("f-mod00001" in ids(sections.getValue("anchored")), "a module is not an anchor")
        assertTrue("tasks/TER-8.md:1" in ids(sections.getValue("anchored")))
        assertEquals(listOf("f-machine1"), ids(sections.getValue("machine")))
    }

    @Test
    fun `an entry a landed check covers leaves the ranking, and with all asked it names the check`() {
        world.export(
            entries = listOf(
                entry("f-cover001", "total adds tax before rounding", files = listOf("billing/.../Billing.kt")).copy(superseded = true, coveredBy = "TER-9@abc1234"),
                entry("f-live0001", "total adds tax before rounding twice", files = listOf("billing/.../Billing.kt")),
            ),
        )
        assertEquals(listOf("f-live0001"), ids(rank(listOf(BILLING), listOf("total")).getValue("facts")), "the covered entry is not briefed")
        val all = runBlocking { world.service.rank(BrainRankRequest(world.workspace.toString(), files = listOf(BILLING), terms = listOf("total"), all = true)) }.sections.getValue("facts")
        assertEquals("TER-9@abc1234", all.hits.single { it.id == "f-cover001" }.coveredBy)
    }

    @Test
    fun `the answer says what the code index found, and a limit reports how many more matched`() {
        world.export(entries = (1..6).map { entry("f-many000$it", "total adds tax before rounding $it", files = listOf("billing/.../Billing.kt")) })
        val result = runBlocking {
            world.service.rank(BrainRankRequest(world.workspace.toString(), files = listOf(BILLING, "missing/Nothing.kt"), terms = listOf("total"), sections = listOf(BrainSection(limit = 4))))
        }
        assertEquals(4, result.sections.getValue("facts").hits.size)
        assertEquals(2, result.sections.getValue("facts").more)
        val code = result.code!!
        assertEquals(listOf(BILLING), code.files.map { it.path })
        assertEquals("billing", code.files.single().module)
        assertEquals(1, code.files.single().callerFiles, "Checkout.kt calls Billing")
        assertEquals(listOf("TER-1"), code.files.single().tasks)
        assertTrue(code.notes.any { "missing/Nothing.kt" in it || "1 of 2" in it }, code.notes.toString())
    }

    @Test
    fun `a file the index does not know ranks by text alone and says so`() {
        world.export(entries = listOf(entry("f-text0001", "total adds tax before rounding", files = listOf("billing/.../Billing.kt"))))
        val result = runBlocking { world.service.rank(BrainRankRequest(world.workspace.toString(), files = listOf("nowhere/Else.kt"), terms = listOf("total", "rounding"))) }
        val hit = result.sections.getValue("facts").hits.single()
        assertEquals(listOf("text"), hit.why)
        assertTrue(result.code!!.files.isEmpty())
    }

    @Test
    fun `an unregistered workspace is refused`() {
        val e = kotlin.runCatching { runBlocking { world.service.rank(BrainRankRequest("C:/nowhere/at/all")) } }.exceptionOrNull()
        assertTrue(e is BrainException && "no registered workspace" in e.message.orEmpty(), e.toString())
    }

    @Test
    fun `the roles and the places to watch come from the export`() {
        world.export(entries = emptyList())
        val index = runBlocking { world.service.index(world.root) }
        assertEquals(40, index.info.briefs.getValue("planner").limit)
    }
}
