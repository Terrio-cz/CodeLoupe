package codeloupe.reconcile

import codeloupe.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlanHashTest {
    private fun daemon(rssMb: Int, verdict: Verdict = Verdict.CONFIRM) = PlanEntry(
        "process:42:1000", TargetKind.PROCESS, "gradle-daemon pid 42 ($rssMb MB)", "Terrio", "TER-1", null, WorkspaceState.LANDED, verdict, "landed",
    )

    @Test
    fun `a process whose memory changed between two reads keeps the plan's hash`() {
        assertEquals(PlanHash.of(listOf(daemon(585))), PlanHash.of(listOf(daemon(612))))
    }

    @Test
    fun `a changed verdict changes the hash`() {
        assertNotEquals(PlanHash.of(listOf(daemon(585))), PlanHash.of(listOf(daemon(585, Verdict.AUTO))))
    }
}
