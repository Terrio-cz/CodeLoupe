package codeloupe.tools

import codeloupe.changes.ChangesQuery
import codeloupe.repo.Registry

object ChangesTool : Tool {
    override val name = "changes"
    override val description = "What a branch or worktree changed against the merge-base with the default branch, by declaration: " +
        "+ added, ~ body changed, ^ signature changed (with the old one), - removed; each with its callers (= exact, candidate " +
        "marked) and the tests that use it. Committed and uncommitted work alike. One call instead of git diff and reading " +
        "the changed files; bodies=true adds a line diff of each changed declaration."
    override val properties = Schema.properties(
        "bodies" to Schema.boolean("Add a compact line diff of every changed declaration"),
        "limit" to Schema.integer(1, 500),
    )
    override val required = emptyList<String>()

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String = registry.changes(root) { set, after, before ->
        ChangesQuery.run(set, after, before, ChangesQuery.Args(bodies = args.bool("bodies") ?: false, limit = args.int("limit") ?: 60))
    }
}
