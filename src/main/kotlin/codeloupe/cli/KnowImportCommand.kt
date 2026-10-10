package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.know.BrainNotation
import codeloupe.know.FactDraft
import codeloupe.know.FactKind
import codeloupe.know.PutBatch
import codeloupe.know.PutResult
import codeloupe.know.StaleReport
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

class KnowImportCommand : KnowSubcommand("import") {
    private val files by argument(help = "Brain notation files (facts.md: one fact per line, + ! ? > = ~ -> prefixes)").multiple(required = true)
    private val scope by option("--scope", help = "Scope of the imported facts; default area:<file name without extension>")
    private val roots by option("--root", help = "Checkout a cited path is relative to; repeat, the first that holds the file wins (default: the current directory)").multiple()
    private val plusKind by option("--plus-kind", help = "Kind of `+` lines: decision, pattern, blocker, environment or other (default: pattern in files named for patterns, else environment)")
    private val dryRun by option("--dry-run", help = "Parse and count only, write nothing").flag()

    override fun help(context: Context) =
        "Import brain notation files: kind from the prefix, the first task id as source, cited paths as file anchors (checked, not fingerprinted). Repeats are merged."

    override fun run() {
        val checkouts = roots.ifEmpty { listOf(cwd()) }
        var failed = false
        for (name in files) {
            val file = Path.of(name)
            if (!Files.isRegularFile(file)) {
                echo("no such file: $name", err = true)
                failed = true
                continue
            }
            val stem = file.fileName.toString().substringBeforeLast('.')
            val kind = plusKind?.let(FactKind::parse) ?: if ("pattern" in stem) FactKind.PATTERN else null
            val parsed = BrainNotation.parse(Files.readString(file), scope ?: "area:$stem", kind) { path -> checkouts.firstOrNull { Files.isRegularFile(Path.of(it).resolve(path)) } ?: checkouts.first() }
            val cited = parsed.facts.count { it.anchors.isNotEmpty() }
            if (dryRun) {
                echo("$name: ${parsed.lines} lines, ${parsed.facts.size} facts, ${parsed.skipped} skipped, $cited cite a path (dry run)")
                continue
            }
            val results = parsed.facts.chunked(BATCH).flatMap { send(it, checkouts.first()) }
            val updated = results.count { it.action == "updated" }
            val gone = goneFacts()
            val dead = results.map { it.fact.id }.distinct().count { it in gone }
            echo("$name: ${parsed.lines} lines, ${parsed.facts.size} facts, ${parsed.skipped} skipped; ${results.size - updated} added, $updated merged into an older fact; $cited cite a path, $dead of them to a file that is gone")
        }
        if (failed) throw ProgramResult(1)
    }

    // Read after the import, once the index of every checkout has had its chance to build: ids of the facts that cite a file that is gone.
    private fun goneFacts(): Set<String> {
        val report = JsonFormat.json.decodeFromJsonElement(StaleReport.serializer(), call("GET", "/know/stale").second)
        return report.stale.filter { it.fact.staleReason.orEmpty().startsWith("file gone") }.map { it.fact.id }.toSet()
    }

    private fun send(drafts: List<FactDraft>, root: String): List<PutResult> {
        val body = buildJsonObject {
            put("facts", JsonArray(drafts.map { JsonFormat.json.encodeToJsonElement(FactDraft.serializer(), it.copy(root = root)) }))
        }
        return JsonFormat.json.decodeFromJsonElement(PutBatch.serializer(), call("POST", "/know", body).second).results
    }

    private companion object {
        const val BATCH = 100
    }
}
