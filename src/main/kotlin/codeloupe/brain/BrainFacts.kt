package codeloupe.brain

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads one topic's entries from `<root>/brain/facts/<topic>.md`, read-only. A stand-in until `brain.mjs show <topic> --json`
 * exists: it keeps only what the file format makes obvious (entry lines, a trailing `<!-- stamp -->`) and nothing else.
 */
object BrainFacts {
    /** One entry. [mark] is the leading marker character (`+ ! = x ?`), [stamp] the text of a trailing HTML comment. */
    class Entry(val mark: String?, val text: String, val stamp: String?)

    class Topic(val bytes: Long, val entries: List<Entry>)

    private val stamp = Regex("""\s*<!--\s*(.*?)\s*-->\s*$""")
    private val marked = Regex("""^([+!=x?~-])\s+(.*)$""")

    /** The topic, or null when the name is not a topic name, the file does not exist, or it is too large to show. */
    fun read(root: Path, topic: String): Topic? {
        if (!BrainTopicName.valid(topic)) return null
        val facts = root.resolve("brain").resolve("facts").toAbsolutePath().normalize()
        val file = facts.resolve("$topic.md").normalize()
        if (!file.startsWith(facts) || !Files.isRegularFile(file)) return null
        val size = runCatching { Files.size(file) }.getOrNull() ?: return null
        if (size > MAX_BYTES) return null
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        return Topic(size, parse(text))
    }

    internal fun parse(text: String): List<Entry> = text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("# ") && !it.startsWith("Durable facts.") }
        .map { line ->
            val found = stamp.find(line)
            val body = if (found != null) line.removeRange(found.range).trimEnd() else line
            val mark = marked.find(body)
            if (mark != null) Entry(mark.groupValues[1], mark.groupValues[2], found?.groupValues?.get(1)) else Entry(null, body, found?.groupValues?.get(1))
        }
        .filter { it.text.isNotEmpty() }
        .toList()

    const val MAX_BYTES = 2L * 1024 * 1024
}
