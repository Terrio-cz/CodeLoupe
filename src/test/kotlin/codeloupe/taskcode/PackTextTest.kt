package codeloupe.taskcode

import codeloupe.TestRepos
import codeloupe.tracker.IssueComment
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The text parts of the task_context pack that need no repository: description, comments, the AGENTS.md lines and the documents. */
class PackTextTest {
    private fun comment(i: Int, text: String) = IssueComment("c$i", "dev", 1_791_500_000_000L + i * 60_000, null, text)

    @Test
    fun `the description shows every section but the checklist, each capped with a pointer to the rest`() {
        val description = "## Context\nWhy it matters.\n\n## Scope\n" + "line of scope\n".repeat(200) + "\n## Acceptance criteria\n- [ ] one\n- [x] two\n\n## Verification\nRun the suite.\n"
        val text = IssueDescription.render(description, perSection = 300, total = 2000)
        assertContains(text, "### Context\nWhy it matters.")
        assertContains(text, "### Verification\nRun the suite.")
        assertFalse("Acceptance criteria" in text, "the checklist is the issue section's: $text")
        assertContains(text, Regex("… \\+\\d+ chars \\(issue sections=\\[\"Scope\"]\\)"))
        assertTrue(text.length < 700, "${text.length} chars")
    }

    @Test
    fun `a description past the total only names the sections it no longer shows`() {
        val description = (1..8).joinToString("\n") { "## S$it\n" + "word ".repeat(120) }
        val text = IssueDescription.render(description, perSection = 600, total = 900)
        assertContains(text, Regex("### S\\d \\(\\d+ chars; issue sections=\\[\"S\\d\"]\\)"))
        assertTrue(text.length < 1600, "${text.length} chars")
    }

    @Test
    fun `short comments stay whole and long ones keep their beginning and their conclusion`() {
        val long = "# Round 1 review. " + "detail ".repeat(400) + "Result: changes requested for the cursor scope."
        val digest = CommentDigest.render(listOf(comment(1, "Plan: use the existing codec."), comment(2, long), comment(3, "Done: merged abc123.")), total = 1000)
        assertTrue(digest.startsWith("3 comments, 1 cut"), digest)
        assertContains(digest, "dev 2026-10-0")
        assertContains(digest, ": Plan: use the existing codec.\n")
        assertContains(digest, "Done: merged abc123.")
        assertContains(digest, "# Round 1 review.")
        assertContains(digest, Regex("… \\+\\d+ chars … .*Result: changes requested for the cursor scope\\.\n"))
        assertTrue(digest.length < 1300, "${digest.length} chars")
        assertEquals("", CommentDigest.render(emptyList()))
    }

    @Test
    fun `a long thread gives every comment a share instead of letting the first ones take the budget`() {
        val thread = (1..30).map { comment(it, "Decision $it: " + "because ".repeat(100)) }
        val digest = CommentDigest.render(thread, total = 5000)
        (1..30).forEach { assertContains(digest, "Decision $it:") }
        assertTrue(digest.length < 7000, "${digest.length} chars")
    }

    @Test
    fun `the norms pick the bullets that name the touched module and leave out words every bullet uses`() {
        val root = TestRepos.tmpDir("norms")
        root.resolve("AGENTS.md").writeText(
            "# Repo\n\n## Modules\n- `:accounts` owns accounts and sessions; tenancy is enforced on the server.\n- `:public-api` owns the HTTP contracts.\n" +
                "## Rules\n- Never push to master without review.\n- Every module keeps its tests next to the code.\n- Every module documents its public types.\n",
        )
        val terms = NormTerms.of(listOf("accounts/src/main/kotlin/demo/accounts/service/AccountService.kt"))
        val text = AgentsNorms.render(root, terms)
        assertContains(text, "AGENTS.md 9 lines, sections (first line): Repo 1 · Modules 3 · Rules 6")
        assertContains(text, "L4 [Modules] - `:accounts` owns accounts")
        assertFalse("public-api" in text.substringAfter("bullets that name"), text)
        assertFalse("Never push" in text, text)
        assertEquals("", AgentsNorms.render(TestRepos.tmpDir("nonorms"), terms))
    }

    @Test
    fun `without a matching bullet the norms are only the section index`() {
        val root = TestRepos.tmpDir("norms2")
        root.resolve("AGENTS.md").writeText("# Repo\n## Rules\n- Be kind.\n")
        assertEquals("AGENTS.md 3 lines, sections (first line): Repo 1 · Rules 2", AgentsNorms.render(root, NormTerms.of(listOf("billing/src/main/kotlin/Billing.kt"))))
    }

    @Test
    fun `documents are listed when they name a touched file or a distinctive class name`() {
        val root = TestRepos.tmpDir("docs")
        root.resolve("docs").createDirectories()
        root.resolve("docs/design.md").writeText("# Design\nThe HttpOpenApi module builds the documents.\nSee Application.kt for the wiring.\n")
        root.resolve("docs/other.md").writeText("# Other\nThe Application starts first.\n")
        val terms = NormTerms.of(listOf("app/src/main/kotlin/demo/http/HttpOpenApi.kt", "app/src/main/kotlin/demo/Application.kt"))
        val text = DocMentions.render(root, terms)
        assertContains(text, "docs/design.md (2 lines): L2 The HttpOpenApi module builds the documents. | L3 See Application.kt for the wiring.")
        assertFalse("other.md" in text, "a bare common class name is not enough: $text")
    }

    @Test
    fun `files in a nested folder are found and a huge or absent docs folder costs nothing`() {
        val root = TestRepos.tmpDir("docs2")
        assertEquals("", DocMentions.render(root, NormTerms.of(listOf("app/src/main/kotlin/demo/HttpOpenApi.kt"))))
        Files.createDirectories(root.resolve("docs/deep/er"))
        root.resolve("docs/deep/er/x.md").writeText("HttpOpenApi.kt\n")
        assertEquals("", DocMentions.render(root, NormTerms.of(listOf("app/src/main/kotlin/demo/HttpOpenApi.kt"))), "only two levels of docs are scanned")
    }
}
