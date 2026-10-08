package codeloupe.metrics

import codeloupe.TestRepos
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaselineTest {
    private val file = TestRepos.tmpDir("baseline").resolve("baseline.json")

    @Test
    fun `a baseline keeps the mean cost of one run per role and skips a role without runs or cost`() {
        val baseline = Baseline.of(BaselineFixture.report("b", Triple("reviewer", 4, 1_600), Triple("coder", 3, 900), Triple("idle", 1, 0)))
        assertEquals(400.0, baseline.meanCost("reviewer"))
        assertEquals(300.0, baseline.meanCost("coder"))
        assertNull(baseline.meanCost("idle"))
        assertNull(baseline.meanCost("planner"))
        assertEquals(2, baseline.roles)
        assertEquals(8, baseline.runs)
    }

    @Test
    fun `the store reports no file, an unreadable one and a loaded one, and follows the file when it changes`() {
        val store = BaselineStore(file)
        assertEquals(BaselineStore.State.Missing, store.state())

        Files.createDirectories(file.parent)
        Files.writeString(file, "not json")
        assertIs<BaselineStore.State.Unreadable>(store.state())

        BaselineFixture.write(file, BaselineFixture.report("first", Triple("main", 2, 600)))
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        val first = assertIs<BaselineStore.State.Loaded>(store.state())
        assertEquals("first", first.baseline.label)
        assertEquals(300.0, first.baseline.meanCost("main"))

        BaselineFixture.write(file, BaselineFixture.report("second", Triple("main", 1, 100)))
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 10_000))
        assertEquals("second", assertIs<BaselineStore.State.Loaded>(store.state()).baseline.label)

        Files.delete(file)
        assertEquals(BaselineStore.State.Missing, store.state())
    }

    @Test
    fun `a report without any role that has runs and a cost is not a usable baseline`() {
        BaselineFixture.write(file, BaselineFixture.report("empty", Triple("main", 1, 0)))
        val state = assertIs<BaselineStore.State.Unreadable>(BaselineStore(file).state())
        assertTrue(state.message.contains("no role"))
    }
}
