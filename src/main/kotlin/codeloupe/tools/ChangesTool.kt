package codeloupe.tools

import codeloupe.changes.ChangesQuery
import codeloupe.repo.Registry

object ChangesTool : Tool {
    override val name = "changes"
    override val description = "What a branch or worktree changed against the merge-base with the default branch, committed and " +
        "uncommitted, by declaration: + added, ~ body changed, ^ signature changed (with the old one), - removed. `./f` is a file " +
        "in the directory of the one above, a lone `[T]` line heads its members. callers=true adds callers (= exact, candidate " +
        "marked) and tests, bodies=true a line diff, tests=true the Gradle command for the tests that use the changes."
    override val properties = Schema.properties(
        "bodies" to Schema.boolean("Add a compact line diff of every changed declaration"),
        "callers" to Schema.boolean("Also each declaration's callers and tests"),
        "tests" to Schema.boolean("Instead of the list: the Gradle command that runs the tests which use the changed declarations"),
        "limit" to Schema.integer(1, 500),
    )
    override val required = emptyList<String>()

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String = registry.changes(root) { set, after, before ->
        ChangesQuery.run(set, after, before, ChangesQuery.Args(bodies = args.bool("bodies") ?: false, limit = (args.int("limit") ?: 60).coerceIn(1, 500), callers = args.bool("callers") ?: false, tests = args.bool("tests") ?: false))
    }
}
