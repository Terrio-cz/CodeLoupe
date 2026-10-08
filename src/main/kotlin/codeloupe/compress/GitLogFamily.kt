package codeloupe.compress

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** `git log` in its default format: one line per commit (short hash, date, author, subject); `--oneline` is only capped. */
object GitLogFamily : Family {
    private val COMMAND = Regex("""(^|[\s/\\])git(\.exe)?\s+(-\S+\s+(\S+\s+)?)*log\b""")
    private val DATE = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy Z", Locale.ENGLISH)
    private const val COMMITS = 40

    override fun matches(line: String) = COMMAND.containsMatchIn(line)

    override fun compress(text: String, cwd: String): String {
        val lines = text.lines()
        if (lines.none { it.startsWith("commit ") }) {
            val kept = lines.filter { it.isNotBlank() }
            return (kept.take(COMMITS) + listOfNotNull(kept.size.takeIf { it > COMMITS }?.let { "… +${it - COMMITS} more commits" })).joinToString("\n")
        }
        class Entry(val sha: String, val date: String, val author: String, val subject: String, val extra: Int)
        val commits = ArrayList<Entry>()
        var i = 0
        while (i < lines.size) {
            if (!lines[i].startsWith("commit ")) {
                i++
                continue
            }
            val sha = lines[i].removePrefix("commit ").trim().take(7)
            var author = ""
            var date = ""
            var j = i + 1
            while (j < lines.size && !lines[j].startsWith("commit ")) {
                when {
                    lines[j].startsWith("Author:") -> author = lines[j].removePrefix("Author:").substringBefore('<').trim()
                    lines[j].startsWith("Date:") -> date = day(lines[j].removePrefix("Date:").trim())
                }
                j++
            }
            val message = lines.subList(i + 1, j).filter { it.startsWith("    ") }.map { it.trim() }.filter { it.isNotEmpty() }
            val subject = message.firstOrNull().orEmpty()
            commits += Entry(sha, date, author, subject, message.size - 1)
            i = j
        }
        // What every commit shares (one author, one day) is said once, not on each line.
        val authors = commits.map { it.author }.distinct()
        val days = commits.map { it.date }.distinct()
        val shown = commits.take(COMMITS).map { c ->
            listOfNotNull(c.sha, c.date.takeIf { days.size > 1 }, c.author.takeIf { authors.size > 1 }, c.subject).joinToString(" ") + if (c.extra > 0) " (+${c.extra})" else ""
        }
        val common = listOfNotNull(authors.singleOrNull(), days.singleOrNull()).joinToString(" ")
        return (listOfNotNull(common.takeIf { it.isNotEmpty() }?.let { "$it:" }) + shown + listOfNotNull(commits.size.takeIf { it > COMMITS }?.let { "… +${it - COMMITS} more commits" })).joinToString("\n")
    }

    private fun day(text: String): String = runCatching { OffsetDateTime.parse(text, DATE).toLocalDate().toString() }.getOrDefault(text.take(10))
}
