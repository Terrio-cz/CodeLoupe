package codeloupe.brain

import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.TestRepos.git
import codeloupe.config.Config
import codeloupe.config.WorkspacesConfig
import codeloupe.daemon.JobQueue
import codeloupe.repo.Registry
import codeloupe.tracker.TrackerSettings
import codeloupe.tracker.Trackers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A code repository with three modules and a task history (TER-1 changed Billing, TER-2 Checkout, TER-3 Rates), a workspace directory that has a
 * brain, and the services over both. The brain's content is whatever the test puts in [engine]'s export.
 */
class BrainWorld {
    val repo: Path = TestRepos.fixtureRepo("kotlin/sample", mapOf(BILLING to billing(), CHECKOUT to checkout(), RATES to rates()))
    val workspace: Path = TestRepos.tmpDir("brain-world").resolve("hub").also { it.resolve("brain").createDirectories() }
    private val home = TestRepos.tmpDir("brain-home")
    val config = Config(home, 0, 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, overlayCheckMs = 0)
    private val scope = CoroutineScope(Dispatchers.Default)
    val registry = Registry(config, JobQueue(scope))
    val engine = CannedEngine()
    val workspaces = WorkspacesConfig(brains = listOf(workspace.toString()))
    val service: BrainService

    init {
        workspace.resolve("brain/brain.mjs").writeText("// the engine is canned in tests\n")
        listOf("TER-1" to (BILLING to "billing history"), "TER-2" to (CHECKOUT to "checkout history"), "TER-3" to (RATES to "rates history")).forEach { (task, change) ->
            repo.resolve(change.first).writeText(repo.resolve(change.first).toFile().readText() + "// ${change.second}\n")
            git(repo, "add", "-A")
            git(repo, "commit", "-q", "-m", "$task ${change.second}")
        }
        service = BrainService(workspaces, registry, Trackers(emptyList(), TrackerSettings(emptyList()), scope), engine, scope = null, engineEnv = mapOf("CODELOUPE_PORT" to "1"), autoBackfill = false)
    }

    val root: BrainRoot get() = BrainRoot.all(workspaces).single()

    /** Prints [entries] and [findings] as `brain.mjs export` would, with the repo as the workspace's canonical repository. */
    fun export(entries: List<BrainEntry>, findings: List<BrainEntry> = emptyList(), feedback: Map<String, BrainFeedback> = emptyMap(), watch: Path? = null) {
        val brain = workspace.resolve("brain")
        val paths = BrainPaths(
            facts = brain.resolve("facts").toString(), tasks = brain.resolve("tasks").toString(), feedback = brain.resolve("feedback.jsonl").toString(),
            config = brain.resolve("workspace.json").toString(), machineFacts = watch?.toString().orEmpty(),
        )
        val roles = mapOf("planner" to BrainRoleBrief(search = listOf("review-patterns"), tasks = true, limit = 40))
        val snapshot = BrainSnapshot(1, BrainWorkspaceInfo("hub", workspace.toString(), repo.toString().replace('\\', '/'), "TER-\\d{1,6}", listOf("planner"), roles), paths, entries, findings, feedback)
        engine.export = JsonFormat.json.encodeToString(BrainSnapshot.serializer(), snapshot)
    }

    fun close() {
        registry.close()
        listOf(home, workspace.parent, repo).forEach { it.toFile().deleteRecursively() }
    }

    companion object {
        const val BILLING = "billing/src/main/kotlin/com/example/billing/Billing.kt"
        const val CHECKOUT = "ui/src/main/kotlin/com/example/ui/Checkout.kt"
        const val RATES = "tax/src/main/kotlin/com/example/rates/Rates.kt"

        fun billing() = """
            package com.example.billing

            class Billing {
                fun total(amount: Int): Int = amount + tax(amount, 21)

                fun tax(amount: Int, rate: Int): Int = amount * rate / 100

                fun tax(amount: Long): Long = amount / 5
            }
        """.trimIndent() + "\n"

        fun checkout() = """
            package com.example.ui

            import com.example.billing.Billing

            class Checkout(private val billing: Billing) {
                fun pay(amount: Int): Int = billing.total(amount)
            }
        """.trimIndent() + "\n"

        fun rates() = """
            package com.example.rates

            object Rates {
                fun standard(): Int = 21
            }
        """.trimIndent() + "\n"

        fun entry(
            id: String?, text: String, task: String? = "TER-1", topic: String = "review-patterns", kind: String = "finding", files: List<String> = emptyList(),
            anchors: List<String> = emptyList(), symbols: List<String> = emptyList(), at: String = "2026-10-05T10:00", level: String = "workspace", superseded: Boolean = false,
        ) = BrainEntry(
            where = if (id == null) "tasks/${task ?: "TER-0"}.md:1" else "facts/$topic.md:${id.hashCode() and 0xff}", text = text, level = level, topic = topic, id = id, kind = kind, task = task,
            at = at, files = files, symbols = symbols, anchors = anchors, superseded = superseded,
        )

        /** A finding line of a task note. */
        fun finding(task: String, text: String, line: Int = 1, files: List<String> = emptyList(), symbols: List<String> = emptyList()) = BrainEntry(
            where = "tasks/$task.md:$line", text = text, level = BrainEntry.TASK_LEVEL, topic = "tasks", line = line, kind = "finding", task = task, at = "2026-10-06T10:00", files = files, symbols = symbols,
        )
    }
}
