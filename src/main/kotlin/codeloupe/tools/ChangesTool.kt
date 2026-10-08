package codeloupe.tools

import codeloupe.changes.ChangesQuery
import codeloupe.repo.Registry

object ChangesTool : Tool {
    override val name = "changes"
    override val description = "What a branch or worktree changed against the merge-base with the default branch, by declaration: " +
        "+ added, ~ body changed, ^ signature changed (with the old one), - removed; each with its callers (= exact, candidate " +
        "marked) and the tests that use it (not for added ones: callers=true). `./f` is a file in the directory of the one above. Committed and uncommitted work alike. One call instead of git diff and reading " +
        "the changed files; bodies=true adds a line diff of each changed declaration; tests=true prints the Gradle command for the tests that use them."
    override val properties = Schema.properties(
        "bodies" to Schema.boolean("Add a compact line diff of every changed declaration"),
        "callers" to Schema.boolean("Also the callers and tests of added declarations"),
        "tests" to Schema.boolean("Instead of the list: the Gradle command that runs the tests which use the changed declarations"),
        "limit" to Schema.integer(1, 500),
    )
    override val required = emptyList<String>()

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String = registry.changes(root) { set, after, before ->
        ChangesQuery.run(set, after, before, ChangesQuery.Args(bodies = args.bool("bodies") ?: false, limit = (args.int("limit") ?: 60).coerceIn(1, 500), callers = args.bool("callers") ?: false, tests = args.bool("tests") ?: false))
    }
}
