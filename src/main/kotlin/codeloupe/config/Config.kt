package codeloupe.config

import java.nio.file.Path

data class Config(
    val home: Path,
    val port: Int,
    val queryTimeoutMs: Long,
    val buildTimeoutMs: Long,
    val buildHeapMb: Int,
    val defaultRoot: String?,
    /** A worktree check this recent (ms) still counts as fresh: parallel and back-to-back queries share one walk. */
    val overlayCheckMs: Long = 1_000,
    val jobs: JobsConfig = JobsConfig(),
    val workspaces: WorkspacesConfig = WorkspacesConfig(),
    val budgets: BudgetsConfig = BudgetsConfig(),
)
