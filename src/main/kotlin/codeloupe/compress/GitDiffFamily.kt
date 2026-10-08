package codeloupe.compress

/**
 * `git diff` / `git show` as `--stat` (the totals and the biggest files) or as a patch (one line per file with its added
 * and removed lines and hunks; the patch itself stays in the full output).
 */
object GitDiffFamily : Family {
    private val COMMAND = Regex("""(^|[\s/\\])git(\.exe)?\s+(-\S+\s+(\S+\s+)?)*(diff|show)\b""")
    private val STAT = Regex("""^ (.+?)\s+\|\s+(\d+)\s*[+-]*\s*$""")
    private val TOTAL = Regex("""^\s*\d+ files? changed.*$""")
    private const val FILES = 12
    private const val PATCH_FILES = 30

    override fun matches(line: String) = COMMAND.containsMatchIn(line)

    override fun compress(text: String, cwd: String): String {
        val lines = text.lines()
        return if (lines.any { it.startsWith("diff --git ") }) patch(lines) else stat(lines)
    }

    private fun stat(lines: List<String>): String {
        val files = lines.mapNotNull { l -> STAT.find(l)?.let { it.groupValues[1].trim() to it.groupValues[2].toInt() } }
        val total = lines.lastOrNull { TOTAL.matches(it) }?.trim() ?: error("not a stat")
        val top = files.sortedByDescending { it.second }.take(FILES)
        return buildList {
            add(total)
            top.forEach { add("${it.second}  ${it.first}") }
            if (files.size > top.size) add("… +${files.size - top.size} more files, smaller changes")
        }.joinToString("\n")
    }

    private fun patch(lines: List<String>): String {
        class FileDiff(val path: String, var added: Int = 0, var removed: Int = 0, var hunks: Int = 0, var kind: String = "")
        val files = ArrayList<FileDiff>()
        for (line in lines) {
            when {
                line.startsWith("diff --git ") -> files += FileDiff(line.removePrefix("diff --git ").substringAfter(" b/").ifEmpty { line })
                files.isEmpty() -> Unit
                line.startsWith("new file mode") -> files.last().kind = "new "
                line.startsWith("deleted file mode") -> files.last().kind = "deleted "
                line.startsWith("rename to") -> files.last().kind = "renamed "
                line.startsWith("@@") -> files.last().hunks++
                line.startsWith("+") && !line.startsWith("+++") -> files.last().added++
                line.startsWith("-") && !line.startsWith("---") -> files.last().removed++
            }
        }
        val totals = "${files.size} files, +${files.sumOf { it.added }} -${files.sumOf { it.removed }}"
        return buildList {
            add("$totals (patch body in the full output)")
            files.take(PATCH_FILES).forEach { add("${it.kind}${it.path} +${it.added} -${it.removed}" + if (it.hunks > 1) " (${it.hunks} hunks)" else "") }
            if (files.size > PATCH_FILES) add("… +${files.size - PATCH_FILES} more files")
        }.joinToString("\n")
    }
}
