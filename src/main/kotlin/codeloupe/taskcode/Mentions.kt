package codeloupe.taskcode

import codeloupe.tracker.Criterion
import codeloupe.tracker.Section
import codeloupe.tracker.TrackerIssue

/**
 * What an issue names that may be code, read from its summary, description sections and acceptance criteria: paths
 * and symbols in code spans, routes and table names, CamelCase words in prose and module paths. Each mention keeps
 * where it was said, so a prediction can show its evidence.
 */
object Mentions {
    private val CODE_SPAN = Regex("`([^`\\n]+)`")
    private val FENCE = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
    private val URL = Regex("https?://\\S+")
    private val SYMBOL = Regex("^(?:[A-Za-z_][A-Za-z0-9_]*\\.)*[A-Za-z_][A-Za-z0-9_]*(?:\\([^()]*\\))?$")
    private val PATH = Regex("^(?:\\.{3}/|\\./)?(?:[A-Za-z0-9_.\\-]+/)*(?:\\.{3}/)?(?:[A-Za-z0-9_.\\-]+/)*[A-Za-z0-9_\\-]+\\.([A-Za-z0-9]{1,12})(?::\\d+(?:-\\d+)?)?$")
    private val FILE_EXT = setOf("kt", "kts", "java", "sql", "md", "json", "yml", "yaml", "toml", "properties", "gradle", "mjs", "js", "ts", "ps1", "sh", "txt", "csv", "xml", "html", "css", "env", "lock", "bat", "cmd")
    private val ROUTE = Regex("^(?:(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+)?((?:/[A-Za-z0-9_{}.:*\\-]+)+/?)$")
    private val TABLE = Regex("^[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*$")
    private val MODULE = Regex("^[a-z][a-z0-9_\\-]*(?:/[a-z][a-z0-9_\\-]*)+$")
    private val BARE_PATH = Regex("(?<![\\w/`.])(?:[A-Za-z0-9_.\\-]+/)+[A-Za-z0-9_\\-]+\\.(?:kt|kts|java|sql|md|json|ya?ml|toml|properties|gradle|mjs|js|ts|ps1|sh|txt|csv)(?::\\d+(?:-\\d+)?)?(?![\\w/])")
    private val WORD = Regex("(?<![\\w`./])[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+(?![\\w`/])")
    private val SOURCE_EXT = setOf("kt", "kts", "java")

    fun of(issue: TrackerIssue): List<Mention> {
        val out = LinkedHashMap<Pair<Mention.Kind, String>, Mention>()
        fun add(kind: Mention.Kind, text: String, where: String) {
            out.putIfAbsent(kind to text, Mention(kind, text, where))
        }
        scan(issue.summary, "summary", ::add)
        val criteria = Criterion.parse(issue.description)
        for (section in Section.parse(issue.description)) {
            val lines = section.text.lines()
            val prose = lines.filterNot { Criterion.parse(it).isNotEmpty() }.joinToString("\n")
            scan(prose, section.title.ifEmpty { "intro" }, ::add)
        }
        criteria.forEachIndexed { i, c -> scan(c.text, "criterion ${i + 1}", ::add) }
        return out.values.toList()
    }

    private fun scan(text: String, where: String, add: (Mention.Kind, String, String) -> Unit) {
        val unfenced = FENCE.replace(text, " ")
        for (m in CODE_SPAN.findAll(unfenced)) {
            val span = m.groupValues[1].trim().trimEnd(',', ';', ':').let { if ('(' in it) it else it.trimEnd(')') }
            classify(span)?.let { add(it, span, where) }
        }
        val prose = URL.replace(CODE_SPAN.replace(unfenced, " "), " ")
        for (m in BARE_PATH.findAll(prose)) add(Mention.Kind.PATH, m.value, where)
        for (m in WORD.findAll(prose)) add(Mention.Kind.WORD, m.value, where)
    }

    /** What one code span names, or null when it is prose, a command or an option. */
    private fun classify(span: String): Mention.Kind? {
        if (span.isEmpty() || span.length > 160 || span.startsWith("-") || span.startsWith("http")) return null
        val unixed = span.replace('\\', '/')
        return when {
            ROUTE.matches(unixed) -> Mention.Kind.LITERAL
            // `Billing.total` is a member, not a file: a bare name is a path only with a known file extension.
            PATH.matchEntire(unixed)?.let { '/' in unixed || it.groupValues[1].lowercase() in FILE_EXT } == true -> Mention.Kind.PATH
            TABLE.matches(span) -> Mention.Kind.LITERAL
            MODULE.matches(unixed) -> Mention.Kind.MODULE
            SYMBOL.matches(span) && !span.first().isDigit() -> Mention.Kind.SYMBOL
            else -> null
        }
    }

    /** `accounts/.../mail/MailSender.kt:43-50` -> the suffix to look for (`mail/MailSender.kt`) and whether it is source. */
    fun pathSuffix(text: String): Pair<String, Boolean> {
        val unixed = text.replace('\\', '/').removePrefix("./").substringBefore(':')
        val suffix = unixed.substringAfterLast(".../").substringAfterLast("…/")
        return suffix to (suffix.substringAfterLast('.').lowercase() in SOURCE_EXT)
    }

    /** `File.kt:43-50` -> 43; null without a line. */
    fun line(text: String): Int? = text.substringAfter(':', "").substringBefore('-').toIntOrNull()
}
