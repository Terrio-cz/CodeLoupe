package codeloupe.jobs

/**
 * `failed==0 && tests>0`: comparisons of a finished job's numbers, all of which must hold. Fields: exit, tests,
 * passed, failed, skipped, seconds. A count the log did not report makes its comparison false.
 */
class Condition private constructor(private val parts: List<Comparison>, val text: String) {
    private class Comparison(val field: String, val op: String, val value: Long)

    fun holds(job: JobRecord): Boolean = parts.all { c ->
        val actual = value(job, c.field) ?: return@all false
        when (c.op) {
            "==" -> actual == c.value
            "!=" -> actual != c.value
            ">" -> actual > c.value
            ">=" -> actual >= c.value
            "<" -> actual < c.value
            else -> actual <= c.value
        }
    }

    private fun value(job: JobRecord, field: String): Long? = when (field) {
        "exit" -> job.exit?.toLong()
        "tests" -> job.summary?.tests?.toLong()
        "passed" -> job.summary?.passed?.toLong()
        "failed" -> job.summary?.failed?.toLong()
        "skipped" -> job.summary?.skipped?.toLong()
        else -> job.durationMs?.let { it / 1000 }
    }

    companion object {
        val FIELDS = listOf("exit", "tests", "passed", "failed", "skipped", "seconds")
        private val PART = Regex("""^\s*([a-z]+)\s*(==|!=|>=|<=|>|<)\s*(-?\d{1,12})\s*$""")

        fun parse(text: String): Condition {
            val parts = text.split("&&", ",").map { part ->
                val m = PART.matchEntire(part) ?: throw IllegalArgumentException("condition \"${part.trim()}\": write <field><op><number>, e.g. failed==0")
                val field = m.groupValues[1]
                if (field !in FIELDS) throw IllegalArgumentException("unknown field $field; known: ${FIELDS.joinToString()}")
                Comparison(field, m.groupValues[2], m.groupValues[3].toLong())
            }
            return Condition(parts, text.trim())
        }
    }
}
