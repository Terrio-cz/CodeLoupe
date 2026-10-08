package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.secrets.SecretAudit
import codeloupe.secrets.SecretReport
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

class EnvAuditCommand : CliktCommand(name = "audit") {
    private val name by option(help = "Only this variable")
    private val scope by option(help = "Only this scope")
    private val limit by option(help = "How many events (newest first)").int().default(50)
    private val consumers by option("--consumers", help = "Who read each variable, with counts, instead of the event list").flag()
    private val json by option("--json", help = "As JSON").flag()

    override fun help(context: Context) = "The audit of the secret store: reads by consumer, creations, rotations, removals. Names and times, never a value."

    override fun run() {
        val audit = SecretAudit(ConfigLoader.load().home.resolve("secrets").resolve("audit.log"))
        if (consumers) {
            val byKey = audit.consumers().filterKeys { key -> (name == null || key.substringBefore('|') == name) && (scope == null || key.substringAfter('|') == scope) }
            if (json) return echo(JsonFormat.json.encodeToString(MapSerializer(String.serializer(), ListSerializer(SecretAudit.Consumer.serializer())), byKey))
            if (byKey.isEmpty()) return echo("no reads recorded")
            byKey.toSortedMap().forEach { (key, list) -> echo("${key.replace("|", "  ")}: " + list.joinToString(", ") { "${it.consumer} x${it.reads} (last ${it.lastAt.take(19)})" }) }
            return
        }
        val events = audit.recent(limit.coerceIn(1, 1_000), name, scope)
        if (json) echo(JsonFormat.json.encodeToString(ListSerializer(SecretAudit.Event.serializer()), events))
        else if (events.isEmpty()) echo("no audit events") else events.forEach { echo(SecretReport.audit(it)) }
    }
}
