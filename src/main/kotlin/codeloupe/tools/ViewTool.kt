package codeloupe.tools

import codeloupe.query.View
import codeloupe.repo.Registry

/** A tool that reads one view of a worktree: its base index with the worktree's overlay on top. */
interface ViewTool : Tool {
    fun run(view: View, args: ToolArgs): String

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String = registry.query(root) { run(it, args) }
}
