package codeloupe.know

import codeloupe.TestRepos
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Retrieval on a constructed knowledge base of 44 facts in 14 areas: questions are worded differently from the facts, and a question
 * counts as answered when a fact that holds the answer is among the 5 lines returned. The measured set on the real brain files is in docs/plan.md.
 */
class KnowRecallTest {
    private val dir = TestRepos.tmpDir("know")
    private val know = Know(dir.resolve("know.db"), AnchorChecker(NoIndex))

    @AfterTest
    fun cleanUp() {
        know.close()
        dir.toFile().deleteRecursively()
    }

    private fun load() = FACTS.forEach { (scope, kind, text) -> runBlocking { know.put(FactDraft(scope, text, kind)) } }

    @Test
    fun `at least four questions in five find the fact that answers them in five lines`() {
        load()
        val missed = QUESTIONS.filter { (question, answers) ->
            val hits = runBlocking { know.search(question, limit = 5) }
            assertTrue(hits.size <= 5)
            hits.none { hit -> answers.any { it in hit.fact.text } }
        }
        val rate = 1.0 - missed.size.toDouble() / QUESTIONS.size
        assertTrue(rate >= 0.8, "answered ${QUESTIONS.size - missed.size} of ${QUESTIONS.size}; missed: ${missed.map { it.first }}")
    }

    @Test
    fun `a scope filter narrows to the area asked about`() {
        load()
        val hits = runBlocking { know.search("port", scope = "area:daemon", limit = 5) }
        assertTrue(hits.isNotEmpty() && hits.all { it.fact.scopeRef.toString() == "area:daemon" })
    }

    private companion object {
        val FACTS = listOf(
            Triple("area:docker", "environment", "shared compose stack uses fixed container names, so a second project cannot start next to it"),
            Triple("area:docker", "blocker", "never run docker system prune on the shared host, it removes the volume with the imported data"),
            Triple("area:docker", "environment", "task stacks get their own compose project name and their own host ports from the port allocator"),
            Triple("area:docker", "blocker", "the app image is built from the worktree, the build fails when gradlew has CRLF line endings"),
            Triple("area:docker", "environment", "testcontainers needs the docker socket; ryuk reaps leftover containers after the run"),
            Triple("area:git", "decision", "land with a fast-forward push of the task branch to main, never force-push"),
            Triple("area:git", "decision", "commit subjects start with the task id followed by a lowercase imperative summary"),
            Triple("area:git", "decision", "use fetch and merge instead of pull, a pull may rewrite the local branch"),
            Triple("area:git", "environment", "worktrees live in a sibling folder named after the repository, one per task"),
            Triple("area:git", "blocker", "a hook denies git worktree remove --force; leave finished worktrees in place"),
            Triple("area:db", "environment", "the read-only recipe runs psql with default_transaction_read_only and a thirty second statement timeout"),
            Triple("area:db", "decision", "migrations are additive only; an older app must keep running on the newer schema during rollback"),
            Triple("area:db", "environment", "the largest table holds 22 million parcels and needs an index scan, a sequential scan takes minutes"),
            Triple("area:db", "pattern", "geometry columns store SRID 5514 and axis order is flipped for some layers"),
            Triple("area:tests", "environment", "the full test suite needs about 6 GB of RAM, one run at a time holds the machine-wide lock"),
            Triple("area:tests", "pattern", "flaky timing tests are fixed with an injected clock, never with sleeps"),
            Triple("area:tests", "environment", "golden outputs contain hashes of fixture text so fixtures must stay byte-identical on every OS"),
            Triple("area:tests", "environment", "test temp directories are created under one removable folder so the disk does not fill up"),
            Triple("area:daemon", "environment", "the daemon binds 127.0.0.1 only and every route but status needs the token header"),
            Triple("area:daemon", "decision", "webhook deliveries are retried with exponential backoff and signed with HMAC-SHA256"),
            Triple("area:daemon", "environment", "the parse worker is a child process that exits after being idle for a while"),
            Triple("area:daemon", "blocker", "never start a throwaway daemon on the default port, use a temp home and a port from the reserved range"),
            Triple("area:daemon", "decision", "overlay indexes hold only files that differ from the base, so a clean worktree costs almost nothing"),
            Triple("area:review", "pattern", "text drifts from behaviour: reread KDoc, help text and docs after every change"),
            Triple("area:review", "pattern", "a counter that drifts between reruns of an import is a finding"),
            Triple("area:review", "pattern", "resource leak: close streams and statements in use blocks, review every new connection"),
            Triple("area:review", "pattern", "one-sided guards need a test with the opposite boundary row"),
            Triple("area:review", "pattern", "tenancy: every query on account data filters by account id, check new routes"),
            Triple("area:workflow", "decision", "decide yourself and state the choice in one line, numbered option lists asking which are permission requests"),
            Triple("area:workflow", "decision", "long silence reads as stalled, report that a run is going on"),
            Triple("area:workflow", "decision", "close-out writes the evidence attachment, ticks criteria, then moves the state to Done"),
            Triple("area:workflow", "decision", "the queue allows four task windows, more need the owner's word"),
            Triple("area:youtrack", "decision", "comments are Czech and one or two lines, issue descriptions are English"),
            Triple("area:youtrack", "environment", "State values are To do, In Progress, Review, Done"),
            Triple("area:youtrack", "environment", "tick criteria with the check command, matching a substring of the line"),
            Triple("area:product", "decision", "paid plans raise usage limits only, never unlock functions"),
            Triple("area:product", "decision", "attribution travels with every served value on every plan"),
            Triple("area:product", "decision", "share-alike sources need an explicit decision before they are served"),
            Triple("area:app", "environment", "the desktop app talks to the daemon over the same token header as the CLI"),
            Triple("area:release", "environment", "release builds are signed in CI, the certificate never touches a developer machine"),
            Triple("area:metrics", "decision", "benchmarks are measured on a controlled run set and recorded in the plan"),
            Triple("area:metrics", "environment", "the metrics collector reads session transcripts and attributes cost per tool"),
            Triple("area:secrets", "decision", "environment secrets live in the vault, only key names may be printed"),
            Triple("area:ports", "environment", "port range for tasks is reserved, allocations are freed when the workspace is released"),
        )

        // The question, and the fact texts (a distinctive piece of each) that answer it.
        val QUESTIONS = listOf(
            "can I start a second compose project beside the shared one" to listOf("fixed container names"),
            "what happens if I prune docker on the shared machine" to listOf("system prune"),
            "where do task stacks get their ports" to listOf("port allocator", "port range for tasks"),
            "why does the app image build fail with windows line endings" to listOf("CRLF"),
            "how are leftover testcontainers cleaned up" to listOf("ryuk"),
            "how do I land my branch on main" to listOf("fast-forward push"),
            "what format does a commit subject take" to listOf("commit subjects"),
            "may I git pull" to listOf("instead of pull"),
            "where do I create a worktree for a new task" to listOf("sibling folder"),
            "can I remove a finished worktree with force" to listOf("remove --force"),
            "how do I query the database read only" to listOf("read-only recipe"),
            "can a migration drop a column" to listOf("additive only"),
            "why is a query on parcels slow" to listOf("22 million parcels"),
            "which SRID do geometries use" to listOf("SRID 5514"),
            "how much memory does the full test run need" to listOf("6 GB of RAM"),
            "how do I fix a flaky timing test" to listOf("injected clock"),
            "why must fixtures keep their bytes" to listOf("byte-identical"),
            "the disk is full of test temp directories" to listOf("removable folder"),
            "which routes need the token" to listOf("token header"),
            "how often are webhook deliveries retried" to listOf("exponential backoff"),
            "when does the parse worker exit" to listOf("child process that exits"),
            "which port may a throwaway daemon use" to listOf("reserved range"),
            "what does an overlay index contain" to listOf("overlay indexes"),
            "what should I recheck after changing behaviour" to listOf("text drifts"),
            "import counters differ between runs" to listOf("counter that drifts"),
            "what to check for leaked connections" to listOf("resource leak"),
            "how do I test a guard condition" to listOf("one-sided guards"),
            "is account data isolated per tenant" to listOf("tenancy"),
            "should I ask the user which option to take" to listOf("permission requests"),
            "what is the close-out sequence" to listOf("close-out writes"),
            "how many task windows may run" to listOf("four task windows"),
            "which language do comments use in the tracker" to listOf("comments are Czech"),
            "what states can an issue be in" to listOf("State values"),
            "how do I tick an acceptance criterion" to listOf("check command"),
            "may a paid plan unlock a feature" to listOf("never unlock"),
            "where is the attribution of a value" to listOf("attribution travels"),
            "can we serve a share-alike source" to listOf("share-alike sources"),
            "who signs release builds" to listOf("signed in CI"),
            "where are secrets kept" to listOf("live in the vault"),
        )
    }
}
