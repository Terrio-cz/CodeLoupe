package codeloupe.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CriterionEditTest {
    private val description = "## Acceptance criteria\n\n- [ ] Mirror stays within 5 min\n- [x] No polling when idle\n- [ ] Credentials never logged\n- [ ] Credentials never returned\n\n## Verification\n- [ ] not a criterion of the others"

    @Test
    fun `a unique open criterion is ticked and every other line stays`() {
        val edited = CriterionEdit.tick(description, "mirror STAYS")
        assertEquals("Mirror stays within 5 min", edited.text)
        assertEquals(description.replace("- [ ] Mirror stays", "- [x] Mirror stays"), edited.description)
    }

    @Test
    fun `an ambiguous match is refused and names the candidates`() {
        val failure = assertFailsWith<TrackerException> { CriterionEdit.tick(description, "Credentials never") }
        assertTrue(failure.message!!.startsWith("'Credentials never' matches 2 open criteria"), failure.message)
        assertTrue("Credentials never logged" in failure.message!! && "Credentials never returned" in failure.message!!)
        assertEquals("Credentials never logged", CriterionEdit.tick(description, "never logged").text)
    }

    @Test
    fun `a missing, blank or already ticked match is refused`() {
        assertEquals("no open criterion contains 'nothing like it'", assertFailsWith<TrackerException> { CriterionEdit.tick(description, "nothing like it") }.message)
        assertEquals("the criterion text to match is empty", assertFailsWith<TrackerException> { CriterionEdit.tick(description, "  ") }.message)
        assertEquals("already ticked: No polling when idle", assertFailsWith<TrackerException> { CriterionEdit.tick(description, "polling") }.message)
    }

    @Test
    fun `a criterion is rewritten with its box, ticked or not`() {
        val open = CriterionEdit.rewrite(description, "within 5 min", "Mirror stays within 2 min")
        assertEquals(description.replace("within 5 min", "within 2 min"), open.description)
        val done = CriterionEdit.rewrite(description, "polling", "Idle means no requests")
        assertTrue("- [x] Idle means no requests" in done.description)
        assertEquals("Idle means no requests", done.text)
    }

    @Test
    fun `a rewrite needs one line of text and a unique match`() {
        assertFailsWith<TrackerException> { CriterionEdit.rewrite(description, "polling", " ") }
        assertFailsWith<TrackerException> { CriterionEdit.rewrite(description, "polling", "two\nlines") }
        assertTrue(assertFailsWith<TrackerException> { CriterionEdit.rewrite(description, "Credentials", "x") }.message!!.contains("matches 2 criteria"))
        assertTrue(assertFailsWith<TrackerException> { CriterionEdit.rewrite(description, "absent", "x") }.message!!.startsWith("no criterion contains"))
    }

    @Test
    fun `windows line ends and indented bullets survive`() {
        val crlf = "- [ ] First one\r\n  * [ ] Second one\r\ntext"
        assertEquals("- [ ] First one\r\n  * [x] Second one\r\ntext", CriterionEdit.tick(crlf, "second").description)
        assertEquals("- [ ] First one\r\n  * [ ] Second, reworded\r\ntext", CriterionEdit.rewrite(crlf, "second", "Second, reworded").description)
    }
}
