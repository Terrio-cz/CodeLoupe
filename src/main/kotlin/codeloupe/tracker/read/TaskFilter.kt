package codeloupe.tracker.read

/**
 * A YouTrack-like task query turned into SQL over `issues i`: `project: TER`, `state: {In Progress}, Review`,
 * `state: -Done`, `#unresolved` / `#resolved`, `epic: TER-162` (all descendants), `type: Bug`, any field
 * `type: Bug` or `{Fix versions}: 0.3`, `sort: updated|created|id|priority`; the remaining words are full text (prefix match).
 */
class TaskFilter private constructor(val where: String, val args: List<Any?>, val text: String?, val order: String) {
    companion object {
        const val DEFAULT_ORDER = "i.updated DESC"
        const val PRIORITY_ORDER = "coalesce(i.priority_rank, 999), i.updated DESC"
        private val TERM = Regex("""(?:\{([^}]+)\}|([\p{L}_][\p{L}\p{N}_.-]*))\s*:\s*((?:-?(?:\{[^}]*\}|"[^"]*"|[^\s,{}"]+))(?:\s*,\s*-?(?:\{[^}]*\}|"[^"]*"|[^\s,{}"]+))*)""")
        private val VALUE = Regex("""(-?)(?:\{([^}]*)\}|"([^"]*)"|([^\s,{}"]+))""")
        private val ID = Regex("[A-Za-z][A-Za-z0-9_]*-\\d+")

        fun parse(query: String?): TaskFilter {
            val conditions = ArrayList<String>()
            val args = ArrayList<Any?>()
            var order = DEFAULT_ORDER
            var namedIds = false
            var rest = query.orEmpty()
            for (m in TERM.findAll(rest).toList()) {
                // `https://…` in free text is a word, not a field.
                if (m.groupValues[3].startsWith("//")) continue
                val key = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
                val values = VALUE.findAll(m.groupValues[3]).map { v -> (v.groupValues[1] == "-") to (v.groupValues[2] + v.groupValues[3] + v.groupValues[4]).trim() }.toList()
                if (key.equals("sort", ignoreCase = true)) {
                    order = when (values.firstOrNull()?.second?.lowercase()) {
                        "created" -> "i.created DESC"
                        "id" -> "i.project, i.num"
                        "priority" -> PRIORITY_ORDER
                        else -> DEFAULT_ORDER
                    }
                } else {
                    if (key.equals("id", ignoreCase = true) || key.equals("issue", ignoreCase = true)) namedIds = true
                    condition(key, values, args)?.let(conditions::add)
                }
                rest = rest.replace(m.value, " ")
            }
            val words = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
            for (word in words.filter { it.startsWith("#") }) {
                when (word.lowercase()) {
                    "#unresolved", "#open" -> conditions += "i.resolved IS NULL"
                    "#resolved" -> conditions += "i.resolved IS NOT NULL"
                    else -> throw IllegalArgumentException("unknown $word; use #unresolved or #resolved")
                }
            }
            var text = words.filterNot { it.startsWith("#") }.map { it.replace("\"", "") }.filter { it.isNotEmpty() }
            // `issue id: TER-5` is YouTrack's spelling: the word before the key is not a search word.
            if (namedIds) text = text.filterNot { it.equals("issue", ignoreCase = true) }
            // A list of issue ids (`TER-94 TER-477`, `issue id: TER-94, TER-477`) names those issues; as full text it would match none.
            val ids = text.map { it.trimEnd(',') }.filter { it.isNotEmpty() }
            if (ids.any { ID.matches(it) } && ids.all { ID.matches(it) || it.equals("issue", ignoreCase = true) || it.equals("id", ignoreCase = true) }) {
                val named = ids.filter { ID.matches(it) }.distinct()
                conditions += named.joinToString(" OR ", "(", ")") { args += it; "i.id = upper(?)" }
                text = emptyList()
            }
            return TaskFilter(conditions.ifEmpty { listOf("1 = 1") }.joinToString(" AND "), args, text.takeIf { it.isNotEmpty() }?.joinToString(" ") { "\"$it\"*" }, order)
        }

        private fun condition(key: String, values: List<Pair<Boolean, String>>, args: MutableList<Any?>): String? {
            if (values.isEmpty()) return null
            val include = values.filter { !it.first }.map { it.second }
            val exclude = values.filter { it.first }.map { it.second }
            // coalesce: a missing value (no state, no field) is excluded by nothing.
            fun clause(column: (String) -> String): String = listOfNotNull(
                include.takeIf { it.isNotEmpty() }?.joinToString(" OR ", "(", ")") { column(it) },
                exclude.takeIf { it.isNotEmpty() }?.joinToString(" AND ") { "NOT coalesce(${column(it)}, 0)" },
            ).joinToString(" AND ")
            return when (key.lowercase()) {
                "project", "in" -> clause { args += it.uppercase(); "i.project = ?" }
                "state", "type", "priority", "assignee" -> clause { args += it; "i.${key.lowercase()} = ? COLLATE NOCASE" }
                "is" -> clause {
                    when (it.lowercase()) {
                        "open", "unresolved" -> "i.resolved IS NULL"
                        "resolved", "closed" -> "i.resolved IS NOT NULL"
                        else -> throw IllegalArgumentException("is: open or resolved")
                    }
                }
                "epic", "parent" -> clause {
                    require(ID.matches(it)) { "epic: an issue id like TER-162" }
                    args += it
                    args += it
                    // The epic's stored spelling (or the id as given when the epic is not mirrored), so `parent` (binary) uses its index.
                    "i.id IN (WITH RECURSIVE d(id) AS (SELECT id FROM issues WHERE parent = coalesce((SELECT id FROM issues WHERE id = ?), upper(?)) " +
                        "UNION SELECT x.id FROM issues x JOIN d ON x.parent = d.id) SELECT id FROM d)"
                }
                "id", "issue" -> clause { args += it; "i.id = upper(?)" }
                else -> clause {
                    args += key
                    args += it.replace("!", "!!").replace("%", "!%").replace("_", "!_")
                    // Several values of one field are stored joined by ", ".
                    "EXISTS (SELECT 1 FROM fields f WHERE f.issue = i.id AND f.name = ? AND (', ' || f.value || ', ') LIKE ('%, ' || ? || ', %') ESCAPE '!')"
                }
            }
        }
    }
}
