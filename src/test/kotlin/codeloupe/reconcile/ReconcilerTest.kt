package codeloupe.reconcile

import codeloupe.TestRepos
import codeloupe.config.ProtectRule
import codeloupe.config.ReconcileConfig
import codeloupe.docker.OwnershipClass
import codeloupe.docker.ResourceEntry
import codeloupe.docker.ResourceKind
import codeloupe.docker.ResourceReport
import codeloupe.workspace.RepoWorkspaces
import codeloupe.workspace.Workspace
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceState
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcilerTest {
    private var clock = Instant.parse("2026-10-08T12:00:00Z")
    private val config = ReconcileConfig(graceMinutes = 0, retryBaseMinutes = 1, retryMaxMinutes = 8, protect = listOf(ProtectRule(Regex("^keep-"))))
    private val home = TestRepos.tmpDir("reconcile")
    private val stateFile = home.resolve("state.json")
    private val recorded = ArrayList<Pair<ActionResult, String>>()

    /** What Docker holds right now, by key; removing one takes it away unless it is [stuck]. */
    private val docker = LinkedHashMap<String, ResourceEntry>()
    private val stuck = HashSet<String>()
    private var directories: List<Workspace> = emptyList()
    private val removeCalls = ArrayList<String>()

    private fun resource(kind: ResourceKind, name: String, workspace: String, state: WorkspaceState?, ownership: OwnershipClass = OwnershipClass.OWNED) =
        ResourceEntry(kind, "id-$name", listOf(name), ownership, "Terrio", workspace, null, "labels", state, null, "2026-10-01T00:00:00.000Z")

    private fun add(r: ResourceEntry) {
        docker[(if (r.kind == ResourceKind.VOLUME) "volume:" + r.names.single() else "${r.kind.name.lowercase()}:${r.id}")] = r
    }

    private fun reconciler(): Reconciler {
        val executor = { entry: PlanEntry ->
            removeCalls += entry.key
            when {
                entry.key in stuck -> ActionResult(entry.key, entry.kind, entry.name, entry.workspace, ActionOutcome.BLOCKED, "locked")
                docker.remove(entry.key) != null || entry.kind == TargetKind.DIRECTORY -> ActionResult(entry.key, entry.kind, entry.name, entry.workspace, ActionOutcome.REMOVED)
                else -> ActionResult(entry.key, entry.kind, entry.name, entry.workspace, ActionOutcome.GONE)
            }
        }
        return Reconciler(
            config,
            { WorkspaceList("now", listOf(RepoWorkspaces("C:/ws/Terrio", "Terrio", "c", "main", emptyList(), emptyMap(), directories))) },
            { ResourceReport("now", "fake", resources = docker.values.toList()) },
            ReconcilePlanner(config) { clock },
            executor,
            ReconcileState(stateFile, config) { clock },
        ) { result, trigger -> recorded += result to trigger }
    }

    @Test
    fun `a landed workspace's labelled resources are removed by the first run and a second run has nothing to do`() = runBlocking {
        add(resource(ResourceKind.CONTAINER, "app-1", "TER-1", WorkspaceState.LANDED))
        add(resource(ResourceKind.VOLUME, "data-1", "TER-1", WorkspaceState.LANDED))
        val reconciler = reconciler()
        val first = reconciler.run("start", auto = true)
        assertEquals(listOf(ActionOutcome.REMOVED, ActionOutcome.REMOVED), first.actions.map { it.outcome })
        assertTrue(docker.isEmpty())
        assertEquals(emptyList(), reconciler.run("interval", auto = true).actions)
        assertEquals(listOf("start", "start"), recorded.map { it.second })
    }

    @Test
    fun `without auto nothing is removed unasked, a dry run changes nothing`() = runBlocking {
        add(resource(ResourceKind.VOLUME, "data-1", "TER-1", WorkspaceState.LANDED))
        val reconciler = reconciler()
        assertEquals(1, reconciler.plan().counts["auto"])
        assertEquals(emptyList(), reconciler.run("interval", auto = false).actions)
        assertEquals(1, docker.size)
        assertEquals(emptyList(), removeCalls)
    }

    @Test
    fun `a confirm entry waits for its key or its workspace, and a keep or protected entry cannot be forced`() = runBlocking {
        add(resource(ResourceKind.VOLUME, "adopted-2", "TER-2", WorkspaceState.LANDED, OwnershipClass.ADOPTED))
        add(resource(ResourceKind.VOLUME, "abandoned-3", "TER-3", WorkspaceState.ABANDONED))
        add(resource(ResourceKind.VOLUME, "active-4", "TER-4", WorkspaceState.ACTIVE))
        add(resource(ResourceKind.VOLUME, "keep-5", "TER-5", WorkspaceState.LANDED))
        val reconciler = reconciler()
        assertEquals(emptyList(), reconciler.run("manual", auto = true).actions)
        assertEquals(4, docker.size)

        val run = reconciler.run("manual", auto = true, confirm = setOf("volume:adopted-2", "volume:active-4", "volume:keep-5", "volume:nonexistent"), workspaces = setOf("ter-3"))
        val byKey = run.actions.associate { it.key to it.outcome }
        assertEquals(ActionOutcome.REMOVED, byKey["volume:adopted-2"])
        assertEquals(ActionOutcome.REMOVED, byKey["volume:abandoned-3"])
        assertEquals(ActionOutcome.SKIPPED, byKey["volume:active-4"])
        assertEquals(ActionOutcome.SKIPPED, byKey["volume:keep-5"])
        assertEquals(setOf("volume:active-4", "volume:keep-5"), docker.keys)
        assertEquals(setOf("volume:adopted-2", "volume:abandoned-3"), removeCalls.toSet())
    }

    @Test
    fun `a blocked resource is retried with a growing wait, also by a new reconciler after a restart, and goes once it is free`() = runBlocking {
        add(resource(ResourceKind.VOLUME, "data-1", "TER-1", WorkspaceState.LANDED))
        stuck += "volume:data-1"
        assertEquals(ActionOutcome.BLOCKED, reconciler().run("start", auto = true).actions.single().outcome)

        // Within the wait nothing is attempted.
        clock = clock.plus(Duration.ofSeconds(30))
        assertEquals(emptyList(), reconciler().run("retry", auto = true).actions)
        assertEquals(1, removeCalls.size)

        // A new reconciler (the daemon restarted) reads the backoff from the file; the second attempt waits 2 min.
        clock = clock.plus(Duration.ofSeconds(31))
        val restarted = reconciler()
        assertEquals(ActionOutcome.BLOCKED, restarted.run("retry", auto = true).actions.single().outcome)
        assertEquals(2, restarted.plan().entries.single().attempts)
        clock = clock.plus(Duration.ofMinutes(1))
        assertEquals(emptyList(), restarted.run("retry", auto = true).actions)

        // The lock is gone; the next due retry removes it and forgets the failures.
        stuck.clear()
        clock = clock.plus(Duration.ofMinutes(2))
        assertTrue(restarted.retryDue())
        assertEquals(ActionOutcome.REMOVED, restarted.run("retry", auto = true).actions.single().outcome)
        assertTrue(docker.isEmpty())
        assertEquals(false, restarted.retryDue())
    }

    @Test
    fun `the wait stops growing at the configured maximum`() {
        val state = ReconcileState(stateFile, config) { clock }
        repeat(10) { state.failed("k", "locked") }
        assertEquals(clock.plus(Duration.ofMinutes(8)).toString(), state.get("k")!!.nextAttempt)
        assertEquals(10, state.get("k")!!.attempts)
    }

    @Test
    fun `a confirmed orphan directory that is locked is retried without confirming again`() = runBlocking {
        val key = "directory:C:/ws/terrio-worktrees/TER-9"
        directories = listOf(Workspace("C:/ws/terrio-worktrees/TER-9", "TER-9", "directory", WorkspaceState.ORPHAN))
        stuck += key
        val reconciler = reconciler()
        // Unconfirmed it is never touched, not even by an auto run.
        assertEquals(emptyList(), reconciler.run("start", auto = true).actions)
        assertEquals(ActionOutcome.BLOCKED, reconciler.run("manual", auto = true, confirm = setOf(key)).actions.single().outcome)

        stuck.clear()
        clock = clock.plus(Duration.ofMinutes(5))
        assertEquals(ActionOutcome.REMOVED, reconciler.run("retry", auto = true).actions.single().outcome)
    }

    @Test
    fun `docker not answering keeps the backoff and plans nothing for docker`() = runBlocking {
        add(resource(ResourceKind.VOLUME, "data-1", "TER-1", WorkspaceState.LANDED))
        stuck += "volume:data-1"
        val first = reconciler()
        first.run("start", auto = true)
        val down = Reconciler(
            config, { WorkspaceList("now", emptyList()) }, { ResourceReport("now", problems = listOf("no Docker")) }, ReconcilePlanner(config) { clock },
            { error("must not run") }, ReconcileState(stateFile, config) { clock },
        ) { _, _ -> }
        assertEquals(emptyList(), down.plan().entries)
        assertEquals(1, ReconcileState(stateFile, config) { clock }.get("volume:data-1")!!.attempts)
    }

    @Test
    fun `the directory remover deletes a tree with read-only files and tolerates one that is gone`() {
        val tree = home.resolve("tree").createDirectories()
        tree.resolve("sub").createDirectories().resolve("a.txt").writeText("x")
        tree.resolve("ro.txt").writeText("x").also { tree.resolve("ro.txt").toFile().setReadOnly() }
        DirectoryRemover.remove(tree)
        assertTrue(!tree.exists())
        DirectoryRemover.remove(tree)
    }

    @Test
    fun `the executor reports a locked directory as blocked and removes it when the lock is gone`() {
        val dir = home.resolve("TER-9").createDirectories()
        var locked = true
        val executor = ReconcileExecutor({ error("no docker") }) { path: Path -> if (locked) throw IOException("being used by another process") else DirectoryRemover.remove(path) }
        val entry = PlanEntry("directory:$dir", TargetKind.DIRECTORY, dir.toString(), "Terrio", "TER-9", null, WorkspaceState.ORPHAN, Verdict.CONFIRM, "orphan")
        assertEquals(ActionOutcome.BLOCKED, executor.execute(entry).outcome)
        assertTrue(dir.exists())
        locked = false
        assertEquals(ActionOutcome.REMOVED, executor.execute(entry).outcome)
        assertTrue(!dir.exists())
    }
}
