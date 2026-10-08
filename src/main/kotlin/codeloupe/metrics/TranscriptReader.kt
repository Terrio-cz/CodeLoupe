package codeloupe.metrics

import java.nio.file.Files
import kotlin.math.floor

/** Reads one Claude Code transcript (JSON lines) into a [Run]; lines that do not parse are skipped. */
class TranscriptReader(private val categorizer: Categorizer) {
    fun read(source: TranscriptFile): Run {
        val parser = TranscriptParser(source.role)
        Files.newBufferedReader(source.path).use { reader -> reader.lineSequence().filter { it.isNotEmpty() }.forEach(parser::feed) }
        val tools = parser.drain().first.map { toolCall(it, parser.turns) }
        return Run(
            file = source.path.fileName.toString(), kind = source.kind, role = parser.role, ter = source.ter ?: TER.find(parser.firstPrompt)?.value,
            model = parser.model, start = parser.start, end = parser.end, turns = parser.turns, usage = parser.usage, peakContext = parser.peak, tools = tools,
        )
    }

    private fun toolCall(r: ToolResult, turns: Int): ToolCall {
        val after = maxOf(0, turns - r.turn)
        val isShell = r.name == "Bash" || r.name == "PowerShell"
        return ToolCall(
            seq = r.seq, name = r.name, category = categorizer.categorize(r.name, r.input), input = r.input,
            file = r.input["file_path"].str()?.takeIf { it.isNotEmpty() },
            partial = r.name == "Read" && (r.input["offset"] != null || r.input["limit"] != null),
            cmd = if (isShell) CommandKey.of(r.input["command"].str()) else null,
            turn = r.turn, chars = r.chars, err = r.err, ms = r.ms, errText = r.errText, head = r.head,
            carried = r.chars.toLong() * after,
            attr = floor(r.chars / 4.0 * (Usage.WRITE_1H + Usage.READ * after) + 0.5).toLong(),
        )
    }

    private companion object {
        val TER = Regex("TER-\\d+")
    }
}
