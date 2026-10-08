package codeloupe.hooks

import codeloupe.hooks.ShellIntent.Filter

/**
 * Reads a Bash or PowerShell command line for what it does to source code: text searches, file listings by name and
 * reads of whole files or ranges. Anything else (builds, git, a search over a pipe, a read that a pipe cuts short) yields nothing.
 */
class ShellIntents(private val paths: ShellPaths) {
    fun parse(command: String, cwd: String): List<ShellIntent> = parse(ShellWords.split(command), cwd, 0)

    private fun parse(commands: List<SimpleCommand>, start: String, depth: Int): List<ShellIntent> {
        var cwd = start
        val found = ArrayList<ShellIntent>()
        commands.forEachIndexed { index, raw ->
            val words = unwrap(raw.words)
            val command = raw.copy(words = words)
            val program = program(words.firstOrNull())
            val rest = words.drop(1)
            val next = commands.getOrNull(index + 1)?.takeIf { it.piped }
            when (program) {
                "cd", "pushd", "set-location", "sl" -> rest.firstOrNull { !it.startsWith("-") }?.let { target -> paths.resolve(cwd, target)?.let { cwd = it } }
                "bash", "sh", "zsh", "dash" -> if (depth < MAX_DEPTH) found += nested(rest, cwd, depth)
                "powershell", "pwsh" -> if (depth < MAX_DEPTH) found += nested(rest, cwd, depth)
                "cmd" -> if (depth < MAX_DEPTH) found += nested(rest, cwd, depth)
                "rg", "ripgrep" -> found += ripgrep(command, rest, cwd, next)
                "grep", "egrep", "fgrep", "ag", "ack" -> found += grep(command, rest, cwd, program)
                "git" -> found += gitGrep(command, rest, cwd)
                "find" -> found += find(rest, cwd, depth)
                "cat", "bat", "batcat", "less", "more", "nl", "type" -> if (!command.writes && next == null) found += cat(rest, cwd)
                "head" -> if (next == null) found += headTail(rest, cwd, head = true)
                "tail" -> if (next == null) found += headTail(rest, cwd, head = false)
                "sed" -> if (next == null) found += sed(rest, cwd)
                "get-content", "gc" -> if (next == null) found += getContent(rest, cwd)
                "select-string", "sls" -> found += selectString(command, rest, cwd, commands.getOrNull(index - 1))
            }
        }
        return found
    }

    private fun nested(rest: List<String>, cwd: String, depth: Int): List<ShellIntent> {
        val at = rest.indexOfFirst { SHELL_COMMAND_FLAG.matches(it) }
        val script = rest.getOrNull(at + 1)?.takeIf { at >= 0 } ?: return emptyList()
        return parse(ShellWords.split(script), cwd, depth + 1)
    }

    private fun program(word: String?): String =
        word?.replace('\\', '/')?.substringAfterLast('/')?.lowercase()?.removeSuffix(".exe")?.removeSuffix(".cmd").orEmpty()

    /** The command without what runs it: variable assignments, `time`, `timeout 60`, `env`, `sudo`. */
    private fun unwrap(words: List<String>): List<String> {
        var rest = words
        while (rest.isNotEmpty()) {
            val first = rest.first()
            rest = when {
                ASSIGNMENT.matches(first) -> rest.drop(1)
                program(first) in PREFIXES -> rest.drop(1)
                program(first) == "timeout" -> rest.drop(1).dropWhile { it.startsWith("-") }.drop(1)
                program(first) == "nice" -> rest.drop(1).let { if (it.firstOrNull() == "-n") it.drop(2) else it.dropWhile { w -> w.startsWith("-") } }
                first in LOOP_WORDS -> rest.drop(1)
                else -> return rest
            }
        }
        return rest
    }

    // --- rg, grep ---------------------------------------------------------------------------------------------------

    private class Options {
        val positionals = ArrayList<String>()
        val patterns = ArrayList<String>()
        val globs = ArrayList<String>()
        val flags = HashSet<String>()
    }

    /** The options of a grep-like command: [valued] take an argument; short flags may be bundled (`-rn`, `-A3`). */
    private fun options(words: List<String>, shortValued: String, longValued: Set<String>): Options {
        val out = Options()
        var k = 0
        fun value(name: String, value: String) {
            when (name) {
                "e", "regexp" -> out.patterns += value
                "g", "glob", "iglob", "include", "t", "type" -> out.globs += if (name == "t" || name == "type") "type:$value" else value
            }
        }
        while (k < words.size) {
            val w = words[k++]
            when {
                w == "--" -> { out.positionals += words.drop(k); return out }
                w.startsWith("--") -> {
                    val name = w.substring(2).substringBefore('=')
                    if ('=' in w) value(name, w.substringAfter('=')) else if (name in longValued && k < words.size) value(name, words[k++]) else out.flags += name
                }
                w.length > 1 && w.startsWith("-") -> {
                    var j = 1
                    while (j < w.length) {
                        val ch = w[j]
                        if (ch in shortValued) {
                            val inline = w.substring(j + 1)
                            if (inline.isNotEmpty()) value(ch.toString(), inline) else if (k < words.size) value(ch.toString(), words[k++])
                            break
                        }
                        out.flags += ch.toString()
                        j++
                    }
                }
                else -> out.positionals += w
            }
        }
        return out
    }

    private fun ripgrep(command: SimpleCommand, rest: List<String>, cwd: String, next: SimpleCommand?): List<ShellIntent> {
        val o = options(rest, "ABCEefgjMmrTtd", RG_VALUED)
        val explicit = o.patterns.isNotEmpty() || "regexp" in o.flags
        val positional = o.positionals
        val files = "files" in o.flags
        val pattern = if (explicit || files) null else positional.firstOrNull()
        val targets = (if (explicit || files) positional else positional.drop(1)).mapNotNull { paths.resolve(cwd, it) }
        val unresolved = (if (explicit || files) positional else positional.drop(1)).size != targets.size
        if (unresolved) return emptyList()
        // A search with nothing to search in, reading what a pipe delivers.
        if (command.piped && targets.isEmpty()) return emptyList()
        val filter = filterOf(o.globs)
        if (files) return fileList(o.globs, targets, cwd)
        val patterns = if (explicit) o.patterns else listOfNotNull(pattern)
        if (patterns.isEmpty()) return emptyList()
        return listOf(ShellIntent.Search(patterns, "i" in o.flags || "ignore-case" in o.flags, "w" in o.flags || "word-regexp" in o.flags, "F" in o.flags || "fixed-strings" in o.flags, targets, cwd, filter))
    }

    private fun fileList(globs: List<String>, roots: List<String>, cwd: String): List<ShellIntent> {
        val names = globs.filter { !it.startsWith("!") && !it.startsWith("type:") }
        val types = globs.filter { it.startsWith("type:") }.map { it.removePrefix("type:") }
        return if (names.isEmpty() && types.isEmpty()) emptyList() else listOf(ShellIntent.FileList(names + types.map { "*.${typeExtension(it)}" }, roots, cwd))
    }

    private fun typeExtension(type: String) = when (type.lowercase()) { "kotlin" -> "kt"; else -> type.lowercase() }

    private fun grep(command: SimpleCommand, rest: List<String>, cwd: String, program: String): List<ShellIntent> {
        val o = options(rest, "efmABCdD", GREP_VALUED)
        val explicit = o.patterns.isNotEmpty()
        val operands = if (explicit) o.positionals else o.positionals.drop(1)
        val pattern = if (explicit) null else o.positionals.firstOrNull()
        val recursive = "r" in o.flags || "R" in o.flags || "recursive" in o.flags || "dereference-recursive" in o.flags || program == "ag" || program == "ack"
        val targets = operands.mapNotNull { paths.resolve(cwd, it) }
        if (targets.size != operands.size) return emptyList()
        // `grep pattern` with no file reads standard input; a recursive one searches the working directory.
        if (targets.isEmpty() && !recursive) return emptyList()
        if (targets.isEmpty() && command.piped) return emptyList()
        val patterns = if (explicit) o.patterns else listOfNotNull(pattern)
        if (patterns.isEmpty()) return emptyList()
        val fixed = "F" in o.flags || "fixed-strings" in o.flags || program == "fgrep"
        return listOf(ShellIntent.Search(patterns, "i" in o.flags || "ignore-case" in o.flags, "w" in o.flags || "word-regexp" in o.flags, fixed, targets, cwd, filterOf(o.globs)))
    }

    private fun gitGrep(command: SimpleCommand, rest: List<String>, cwd: String): List<ShellIntent> {
        var k = 0
        var dir = cwd
        while (k < rest.size && rest[k] != "grep") {
            if (rest[k] == "-C" && k + 1 < rest.size) { dir = paths.resolve(dir, rest[k + 1]) ?: return emptyList(); k += 2 } else if (rest[k].startsWith("-")) k++ else return emptyList()
        }
        if (k >= rest.size) return emptyList()
        val o = options(rest.drop(k + 1), "efmABCc", GREP_VALUED)
        val explicit = o.patterns.isNotEmpty()
        val operands = (if (explicit) o.positionals else o.positionals.drop(1)).filter { it != "--" }
        val pattern = if (explicit) null else o.positionals.firstOrNull()
        val targets = operands.mapNotNull { paths.resolve(dir, it) }
        if (targets.size != operands.size) return emptyList()
        val patterns = if (explicit) o.patterns else listOfNotNull(pattern)
        if (patterns.isEmpty() || command.piped && targets.isEmpty()) return emptyList()
        return listOf(ShellIntent.Search(patterns, "i" in o.flags || "ignore-case" in o.flags, "w" in o.flags || "word-regexp" in o.flags, "F" in o.flags || "fixed-strings" in o.flags, targets, dir, filterOf(o.globs)))
    }

    private fun filterOf(globs: List<String>): Filter {
        val positive = globs.filter { !it.startsWith("!") }
        if (positive.isEmpty()) return Filter.NONE
        return if (positive.any { SourceNames.mentionsSource(it.removePrefix("type:")) }) Filter.SOURCE else Filter.OTHER
    }

    // --- find -------------------------------------------------------------------------------------------------------

    private fun find(rest: List<String>, cwd: String, depth: Int): List<ShellIntent> {
        val roots = ArrayList<String>()
        var k = 0
        while (k < rest.size && !rest[k].startsWith("-") && rest[k] != "(" && rest[k] != "!") roots += paths.resolve(cwd, rest[k++]) ?: return emptyList()
        val names = ArrayList<String>()
        var inner: List<String>? = null
        while (k < rest.size) {
            when (rest[k]) {
                "-name", "-iname" -> { rest.getOrNull(k + 1)?.let { names += it }; k += 2 }
                "-exec", "-execdir", "-ok" -> {
                    val end = rest.drop(k + 1).indexOfFirst { it == ";" || it == "+" || it == "\\;" }
                    inner = rest.drop(k + 1).let { if (end >= 0) it.take(end) else it }
                    k += 1 + (if (end >= 0) end + 1 else 0)
                    if (end < 0) break
                }
                else -> k++
            }
        }
        val where = roots.ifEmpty { listOf(cwd) }
        val search = inner?.takeIf { depth < MAX_DEPTH }?.let { words ->
            val innerIntents = parse(listOf(SimpleCommand(words.map { if (it == "{}") where.first() else it })), cwd, depth + 1)
            innerIntents.filterIsInstance<ShellIntent.Search>().map { it.copy(targets = it.targets.ifEmpty { where }, filter = if (names.any(SourceNames::mentionsSource)) Filter.SOURCE else Filter.OTHER) }
        }.orEmpty()
        if (search.isNotEmpty()) return search
        return if (names.isEmpty()) emptyList() else listOf(ShellIntent.FileList(names, where, cwd))
    }

    // --- reads ------------------------------------------------------------------------------------------------------

    private fun cat(rest: List<String>, cwd: String): List<ShellIntent> =
        rest.filter { !it.startsWith("-") }.mapNotNull { paths.resolve(cwd, it) }.map { ShellIntent.Read(it) }

    private fun headTail(rest: List<String>, cwd: String, head: Boolean): List<ShellIntent> {
        var count = DEFAULT_LINES
        var fromLine: Int? = null
        val files = ArrayList<String>()
        var k = 0
        while (k < rest.size) {
            val w = rest[k++]
            when {
                w == "-n" || w == "--lines" -> { val v = rest.getOrNull(k++).orEmpty(); if (v.startsWith("+")) fromLine = v.drop(1).toIntOrNull() else count = v.removePrefix("-").toIntOrNull() ?: return emptyList() }
                w.startsWith("-n") -> { val v = w.drop(2); if (v.startsWith("+")) fromLine = v.drop(1).toIntOrNull() else count = v.removePrefix("-").toIntOrNull() ?: return emptyList() }
                w.startsWith("--lines=") -> count = w.substringAfter('=').removePrefix("-").toIntOrNull() ?: return emptyList()
                w == "-c" || w == "--bytes" -> return emptyList()
                w.matches(Regex("""-\d+""")) -> count = w.drop(1).toInt()
                w.startsWith("-") -> Unit
                else -> files += w
            }
        }
        val resolved = files.mapNotNull { paths.resolve(cwd, it) }
        return resolved.map { path ->
            when {
                head -> ShellIntent.Read(path, head = count)
                fromLine != null -> ShellIntent.Read(path, from = fromLine)
                else -> ShellIntent.Read(path, tail = count)
            }
        }
    }

    private fun sed(rest: List<String>, cwd: String): List<ShellIntent> {
        var quiet = false
        val scripts = ArrayList<String>()
        val files = ArrayList<String>()
        var k = 0
        while (k < rest.size) {
            val w = rest[k++]
            when {
                w == "-n" || w == "--quiet" || w == "--silent" -> quiet = true
                w == "-e" || w == "--expression" -> rest.getOrNull(k++)?.let { scripts += it }
                w.startsWith("-i") || w == "--in-place" -> return emptyList()
                w.startsWith("-") && w.length > 1 && w.drop(1).all { it in "nEersu" } -> if ('n' in w) quiet = true
                w.startsWith("-") && w.length > 1 -> Unit
                scripts.isEmpty() && files.isEmpty() -> scripts += w
                else -> files += w
            }
        }
        val script = scripts.singleOrNull()?.trim() ?: return emptyList()
        if (!quiet) return emptyList()
        val targets = files.mapNotNull { paths.resolve(cwd, it) }
        val range = SED_RANGE.matchEntire(script)
        if (range != null) {
            val from = range.groupValues[1].toInt()
            val to = range.groupValues[2].takeIf { it.isNotEmpty() && it != "$" }?.toInt()
            return targets.map { ShellIntent.Read(it, from = from, to = to) }
        }
        SED_LINE.matchEntire(script)?.let { return targets.map { path -> ShellIntent.Read(path, from = it.groupValues[1].toInt(), to = it.groupValues[1].toInt()) } }
        if (script == "\$p") return targets.map { ShellIntent.Read(it, from = 1) }
        val grep = SED_GREP.matchEntire(script) ?: return emptyList()
        return listOf(ShellIntent.Search(listOf(grep.groupValues[1]), false, false, false, targets, cwd, Filter.NONE))
    }

    private fun getContent(rest: List<String>, cwd: String): List<ShellIntent> {
        var head: Int? = null
        var tail: Int? = null
        val files = ArrayList<String>()
        var k = 0
        while (k < rest.size) {
            val w = rest[k++]
            when (w.lowercase()) {
                "-totalcount", "-head", "-first" -> head = rest.getOrNull(k++)?.toIntOrNull() ?: return emptyList()
                "-tail", "-last" -> tail = rest.getOrNull(k++)?.toIntOrNull() ?: return emptyList()
                "-path", "-literalpath" -> rest.getOrNull(k++)?.let { files += it }
                "-encoding", "-readcount", "-delimiter", "-stream", "-filter", "-include", "-exclude" -> k++
                else -> if (!w.startsWith("-")) files += w
            }
        }
        return files.mapNotNull { paths.resolve(cwd, it) }.map { ShellIntent.Read(it, head = head, tail = tail) }
    }

    private fun selectString(command: SimpleCommand, rest: List<String>, cwd: String, before: SimpleCommand?): List<ShellIntent> {
        var pattern: String? = null
        val files = ArrayList<String>()
        var fixed = false
        var k = 0
        while (k < rest.size) {
            val w = rest[k++]
            when (w.lowercase()) {
                "-pattern" -> pattern = rest.getOrNull(k++)
                "-path", "-literalpath" -> rest.getOrNull(k++)?.let { files += it }
                "-simplematch" -> fixed = true
                "-casesensitive", "-list", "-quiet", "-allmatches", "-notmatch" -> Unit
                "-context", "-encoding", "-include", "-exclude" -> k++
                else -> if (!w.startsWith("-")) { if (pattern == null) pattern = w else files += w }
            }
        }
        val patterns = listOfNotNull(pattern)
        if (patterns.isEmpty()) return emptyList()
        val targets = files.mapNotNull { paths.resolve(cwd, it) }
        if (targets.size != files.size) return emptyList()
        if (targets.isEmpty() && !command.piped) return emptyList()
        // `Get-ChildItem -Recurse -Filter *.kt | Select-String foo`: the listing before the pipe tells where and what.
        val listing = before?.takeIf { command.piped && program(it.words.firstOrNull()) in setOf("get-childitem", "gci", "ls", "dir") }
        val filter = listing?.let { l -> filterOf(l.words.windowed(2).filter { it[0].lowercase() in setOf("-filter", "-include") }.map { it[1] }) } ?: Filter.NONE
        val roots = listing?.words?.drop(1)?.takeWhile { !it.startsWith("-") }?.mapNotNull { paths.resolve(cwd, it) }.orEmpty()
        if (command.piped && listing == null) return emptyList()
        return listOf(ShellIntent.Search(patterns, true, false, fixed, targets.ifEmpty { roots }, cwd, filter))
    }

    private companion object {
        const val MAX_DEPTH = 2
        const val DEFAULT_LINES = 10
        val SHELL_COMMAND_FLAG = Regex("""(?i)-[a-z]*c|-command|/[ck]""")
        val ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")
        val PREFIXES = setOf("time", "command", "builtin", "exec", "nohup", "env", "sudo", "stdbuf")
        val LOOP_WORDS = setOf("do", "then", "else", "!", "{", "}")
        val RG_VALUED = setOf(
            "after-context", "before-context", "context", "glob", "iglob", "type", "type-not", "type-add", "regexp", "file", "max-count", "max-depth",
            "maxdepth", "max-filesize", "replace", "threads", "sort", "sortr", "encoding", "engine", "colors", "color", "context-separator",
            "field-context-separator", "field-match-separator", "path-separator", "pre", "pre-glob", "ignore-file", "max-columns", "dfa-size-limit", "regex-size-limit",
        )
        val GREP_VALUED = setOf(
            "regexp", "file", "max-count", "after-context", "before-context", "context", "include", "exclude", "exclude-dir", "exclude-from", "include-dir",
            "directories", "devices", "label", "binary-files",
        )
        val SED_RANGE = Regex("""(\d+),(\d+|\$)p""")
        val SED_LINE = Regex("""(\d+)p""")
        val SED_GREP = Regex("""/([^/]+)/p""")
    }
}
