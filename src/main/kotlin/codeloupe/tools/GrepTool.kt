package codeloupe.tools

import codeloupe.query.GrepQuery
import codeloupe.query.View

object GrepTool : ViewTool {
    override val name = "grep"
    override val description = "Text search in the indexed source (Kotlin files, worktree edits included) for what find/usages do not " +
        "see: string literals, SQL, annotation arguments, config keys. Literal by default (regex=true for a regex, one line at a " +
        "time). Hits are grouped by file and enclosing declaration, one code line each."
    override val properties = Schema.properties(
        "pattern" to Schema.string("Text to find, or a regex with regex=true"),
        "regex" to Schema.boolean("Treat pattern as a regular expression"),
        "ignoreCase" to Schema.boolean("Case-insensitive match"),
        "module" to Schema.string("Module path prefix, e.g. \"api\" or \"services/billing\""),
        "test" to Schema.boolean("true = only test sources, false = exclude them"),
        "limit" to Schema.integer(1, 500),
    )
    override val required = listOf("pattern")

    override fun run(view: View, args: ToolArgs): String = GrepQuery.run(
        view,
        GrepQuery.Args(
            pattern = args.string("pattern"), regex = args.bool("regex") ?: false, ignoreCase = args.bool("ignoreCase") ?: false,
            module = args.string("module"), test = args.bool("test"), limit = args.int("limit") ?: 40,
        ),
    )
}
