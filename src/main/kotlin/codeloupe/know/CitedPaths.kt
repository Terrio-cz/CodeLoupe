package codeloupe.know

/** Repository paths a line of text cites (`app/src/Foo.kt:88`, `docs/plan.md`): relative, with a directory and a known extension. */
object CitedPaths {
    private const val EXTENSIONS = "kts|kt|java|md|mjs|json|jsonl|js|tsx|ts|ya?ml|sql|properties|gradle|ps1|sh|toml|xml|bak"
    private val CITED = Regex("""(?<![\w@:/.\\-])((?:[\w.-]+/)+[\w.-]*\w\.(?:$EXTENSIONS)(?!\w))(?::\d+(?:-\d+)?)?""")
    private val EXTENSION = Regex("""\.(?:$EXTENSIONS)$""")
    private val LINE_SUFFIX = Regex(""":\d+(?:-\d+)?$""")

    /** Distinct cited paths, in order; URLs, absolute paths and paths with an elided `...` segment are not cited paths. */
    fun of(text: String): List<String> =
        CITED.findAll(text).map { it.groupValues[1] }.filter { path -> path.split('/').none { it == "..." || it == ".." } }.distinct().toList()

    fun looksLikePath(token: String): Boolean = '/' in token || '\\' in token || EXTENSION.containsMatchIn(clean(token))

    /** Slashes forward, no `./` in front, no `:line` behind. */
    fun clean(path: String): String = path.trim().replace('\\', '/').removePrefix("./").replace(LINE_SUFFIX, "")
}
