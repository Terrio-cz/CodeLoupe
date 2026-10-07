package codeloupe.jobs

/**
 * `[<condition> ?] <kind>[:<argument>]`, e.g. `job@gradle-test:./gradlew check`, `failed>0 ? notify:tests failed`,
 * `webhook:http://127.0.0.1:9000/done`. A `job:` command line is split into words and run without a shell.
 */
object ActionParser {
    private val CONDITION = Regex("""^\s*([a-z]+\s*(?:==|!=|>=|<=|>|<)\s*-?\d+(?:\s*(?:&&|,)\s*[a-z]+\s*(?:==|!=|>=|<=|>|<)\s*-?\d+)*)\s*\?\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val ACTION = Regex("""^([a-z]+)(?:@([A-Za-z0-9._:-]{1,64}))?(?::(.*))?$""", RegexOption.DOT_MATCHES_ALL)
    val KINDS = listOf("job", "notify", "webhook")

    fun parse(spec: String): Action {
        val conditional = CONDITION.matchEntire(spec)
        val condition = conditional?.let { Condition.parse(it.groupValues[1]) }
        val body = (conditional?.groupValues?.get(2) ?: spec).trim()
        val m = ACTION.matchEntire(body) ?: throw IllegalArgumentException("action \"$spec\": write <kind>[:<argument>] with kind ${KINDS.joinToString(" | ")}")
        val (kind, slot, argument) = m.destructured
        if (slot.isNotEmpty() && kind != "job") throw IllegalArgumentException("action \"$spec\": only job takes @slot")
        return when (kind) {
            "job" -> {
                val command = CommandLine.split(argument)
                if (command.isEmpty()) throw IllegalArgumentException("action \"$spec\": job needs a command, e.g. job:./gradlew check")
                Action.RunJob(condition, spec, command, slot.ifEmpty { null })
            }
            "notify" -> Action.Notify(condition, spec, argument.trim())
            "webhook" -> {
                if (argument.isBlank()) throw IllegalArgumentException("action \"$spec\": webhook needs a URL")
                Action.Webhook(condition, spec, argument.trim())
            }
            else -> throw IllegalArgumentException("action \"$spec\": unknown kind $kind; known: ${KINDS.joinToString()} (no shell commands)")
        }
    }
}
