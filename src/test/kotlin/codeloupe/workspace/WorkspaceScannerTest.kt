package codeloupe.workspace

import codeloupe.TestRepos
import codeloupe.platform.Timings
import codeloupe.taskcode.TaskPattern
import codeloupe.tracker.read.TaskRow
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceScannerTest {
    private val repo = TestRepos.fixtureRepo("kotlin/sample")
    private val roots = TestRepos.tmpDir("worktrees")
    private val commonDir = repo.resolve(".git").toString().replace('\\', '/')
    private val pattern = TaskPattern.of(repo, listOf("TER", "CL"))

    private fun worktree(branch: String, commitSubject: String? = null): Path {
        val dir = roots.resolve(branch)
        TestRepos.git(repo, "worktree", "add", "-q", "-b", branch, dir.toString(), "main")
        if (commitSubject != null) {
            dir.resolve("$branch.txt").writeText(branch)
            TestRepos.git(dir, "add", "-A")
            TestRepos.git(dir, "commit", "-q", "-m", commitSubject)
        }
        return dir
    }

    private fun row(id: String, state: String, resolved: Boolean) =
        TaskRow(id, "task $id", state, resolved, "Task", "Major", 1, null, 0, emptyList())

    private fun scan(
        abandonedDays: Int = 14,
        now: Instant = Instant.now(),
        withSize: Boolean = false,
        landed: (String) -> Boolean = { false },
        tasks: (String) -> TaskRow? = { null },
    ) = WorkspaceScanner(abandonedDays, tasks, landed, { now }).scan(commonDir, repo, pattern, listOf(roots.toString().replace('\\', '/')), withSize)

    private fun RepoWorkspaces.named(name: String) = workspaces.single { it.name == name }

    @Test
    fun `lists the main worktree and every linked one with branch, task and merge state`() {
        worktree("TER-1", "TER-1 add a file")
        worktree("TER-3")
        val result = scan()
        assertEquals("main", result.defaultRef)
        val main = result.workspaces.first()
        assertEquals("main", main.role)
        assertEquals(WorkspaceState.ACTIVE, main.state)

        val working = result.named("TER-1")
        assertEquals("worktree", working.role)
        assertEquals("TER-1", working.branch)
        assertEquals("TER-1", working.taskId)
        assertEquals(WorkspaceState.ACTIVE, working.state)
        val merge = working.merge!!
        assertEquals(1, merge.ahead)
        assertEquals(false, merge.merged)
        assertEquals("TER-1 add a file", merge.subject)
        assertNotNull(working.lastActivity)

        // Cut from main and not committed on: nothing says it is done.
        val fresh = result.named("TER-3")
        assertEquals(WorkspaceState.ACTIVE, fresh.state)
        assertTrue(fresh.merge!!.merged)
    }

    @Test
    fun `a branch whose commits are on the default branch is landed when the task shows it`() {
        val dir = worktree("TER-2", "TER-2 land me")
        TestRepos.git(repo, "merge", "-q", "--ff-only", "TER-2")
        worktree("TER-6")
        val untitled = worktree("TER-7", "wip")
        TestRepos.git(repo, "merge", "-q", "--ff-only", "TER-7")
        val result = scan(tasks = { id -> if (id == "TER-6") row(id, "Done", true) else null })

        assertEquals(WorkspaceState.LANDED, result.named(dir.name).state, "the HEAD commit names the task")
        assertEquals(WorkspaceState.LANDED, result.named("TER-6").state, "the tracker says Done")
        assertEquals("Done", result.named("TER-6").tracker!!.state)
        assertEquals(WorkspaceState.ACTIVE, result.named(untitled.name).state, "merged, but nothing links the commit to the task")
    }

    @Test
    fun `a merged branch is landed when the default branch holds commits of its task`() {
        worktree("TER-15", "wip")
        TestRepos.git(repo, "merge", "-q", "--ff-only", "TER-15")
        assertEquals(WorkspaceState.ACTIVE, scan().named("TER-15").state)
        assertEquals(WorkspaceState.LANDED, scan(landed = { it == "TER-15" }).named("TER-15").state)
    }

    @Test
    fun `a resolved task whose branch is not merged says so`() {
        worktree("TER-5", "TER-5 squash merged elsewhere")
        val five = scan(tasks = { row(it, "Done", true) }).named("TER-5")
        assertEquals(WorkspaceState.ACTIVE, five.state)
        assertContains(five.note!!, "not on main")
    }

    @Test
    fun `an idle workspace with unmerged work is abandoned`() {
        worktree("TER-4", "TER-4 half done")
        assertEquals(WorkspaceState.ACTIVE, scan().named("TER-4").state)
        val later = scan(abandonedDays = 14, now = Instant.now().plus(Duration.ofDays(15)))
        assertEquals(WorkspaceState.ABANDONED, later.named("TER-4").state)
    }

    @Test
    fun `directories git does not know are orphans, a clone among them says what it is`() {
        worktree("TER-1", "TER-1 work")
        roots.resolve("TER-9").createDirectories().resolve("build.log").writeText("leftover")
        // A worktree whose registration was pruned: its .git file points at an admin dir that is gone.
        val pruned = worktree("TER-10", "TER-10 work")
        TestRepos.git(repo, "worktree", "remove", "--force", "--force", pruned.toString())
        pruned.createDirectories().resolve(".git").writeText("gitdir: ${commonDir}/worktrees/TER-10\n")
        // A separate clone is reported as such; a stray file is not a directory.
        val clone = TestRepos.fixtureRepo("kotlin/sample")
        Files.move(clone, roots.resolve("elsewhere"))
        roots.resolve("notes.txt").writeText("scratch")

        val orphans = scan().workspaces.filter { it.state == WorkspaceState.ORPHAN }
        assertEquals(listOf("elsewhere", "TER-10", "TER-9"), orphans.map { it.name })
        assertEquals("directory", orphans.first().role)
        assertContains(orphans[0].note!!, "separate clone")
        assertContains(orphans[1].note!!, "registration is gone")
        assertContains(orphans[2].note!!, "no .git")
        assertNull(orphans[2].branch)
    }

    @Test
    fun `a registered worktree whose directory is gone is an orphan too`() {
        val dir = worktree("TER-8", "TER-8 work")
        dir.toFile().deleteRecursively()
        val gone = scan().named("TER-8")
        assertEquals(WorkspaceState.ORPHAN, gone.state)
        assertEquals("TER-8", gone.branch)
        assertContains(gone.note!!, "directory is gone")
    }

    @Test
    fun `a detached worktree takes its task from the directory name`() {
        val dir = roots.resolve("TER-11")
        TestRepos.git(repo, "worktree", "add", "-q", "--detach", dir.toString(), "main")
        val detached = scan().named("TER-11")
        assertNull(detached.branch)
        assertEquals("TER-11", detached.taskId)
        assertNotNull(detached.head)
    }

    @Test
    fun `sizes are only summed when asked for`() {
        worktree("TER-12", "TER-12 work")
        assertNull(scan().named("TER-12").sizeBytes)
        assertTrue(scan(withSize = true).named("TER-12").sizeBytes!! > 0)
    }

    @Test
    fun `a scan starts no git process`() {
        worktree("TER-13", "TER-13 work")
        roots.resolve("TER-14").createDirectories()
        val before = Timings.gitSpawns()
        scan()
        assertEquals(before, Timings.gitSpawns())
    }

    @Test
    fun `reading the worktrees on several threads gives the same workspaces as one after another`() {
        for (i in 20..29) worktree("TER-$i", if (i % 3 == 0) "TER-$i work" else null)
        TestRepos.git(repo, "merge", "-q", "--ff-only", "TER-21")
        worktree("TER-40").toFile().deleteRecursively()
        val now = Instant.now()
        val tasks = { id: String -> if (id == "TER-22") row(id, "Done", true) else null }
        val landed = { id: String -> id == "TER-21" }
        fun scan(parallel: Boolean) =
            WorkspaceScanner(14, tasks, landed, { now }, parallel).scan(commonDir, repo, pattern, listOf(roots.toString().replace('\\', '/')), withSize = false)

        val sequential = scan(parallel = false)
        val parallel = scan(parallel = true)
        assertTrue(sequential.workspaces.size >= 12)
        assertEquals(sequential, parallel)
        assertEquals(sequential.workspaces.map { it.path }, parallel.workspaces.map { it.path })
    }
}
