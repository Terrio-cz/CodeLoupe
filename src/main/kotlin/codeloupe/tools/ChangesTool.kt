package codeloupe.tools

import codeloupe.changes.ChangesQuery
import codeloupe.repo.Registry

object ChangesTool : Tool {
    override val name = "changes"
    override val description = "Declarations a branch or worktree changed against the merge-base, committed and uncommitted: + added, ~ body, " +
        "^ signature (old one shown), - removed; `./f` = same directory. callers=true adds callers and tests, bodies=true " +
        "line diffs, tests=true the Gradle command for the affected tests."
    override val properties = Schema.properties(
        "bodies" to Schema.boolean("Line diffs"),
        "callers" to Schema.boolean("Callers and tests"),
        "tests" to Schema.boolean("Gradle command for affected tests"),
        "limit" to Schema.integer(1, 500),
    )
    override val required = emptyList<String>()

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String = registry.changes(root) { set, after, before ->
        ChangesQuery.run(set, after, before, ChangesQuery.Args(bodies = args.bool("bodies") ?: false, limit = (args.int("limit") ?: 60).coerceIn(1, 500), callers = args.bool("callers") ?: false, tests = args.bool("tests") ?: false))
    }
}
