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
    /** Index reads that run at once; more wait their turn, so ten windows asking together do not hold ten reads' memory. */
    val maxParallelQueries: Int = 2,
    /** A repository with more indexed files than this is checked through git alone, not by walking its worktrees. */
    val largeWorktreeFiles: Int = 40_000,
    val jobs: JobsConfig = JobsConfig(),
    val workspaces: WorkspacesConfig = WorkspacesConfig(),
    val metrics: MetricsConfig = MetricsConfig(),
    val budgets: BudgetsConfig = BudgetsConfig(),
)
