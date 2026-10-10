package codeloupe.commands

/**
 * A shell command reduced to the group its calls fall in: the program, its subcommand, the flags (sorted, values dropped) and
 * placeholders for what the call passed (`<str>` a quoted string, `<path>`, `<n>`, `<arg>`). `cd X && git -C wt diff --stat a/b`
 * is `git diff --stat <path>`, `rg -n "x" src/` is `rg -n <str> <path>`, and a command that carries a heredoc is a group of its own
 * (`heredoc write`, `heredoc python`). Only the first command of a chain or pipe counts: what follows is how the output was cut.
 */
object ShellCommand {
    const val HEREDOC_PREFIX = "heredoc "
    private const val EMPTY = "(empty command)"
    private const val CUT = "(command cut off)"
    private const val MAX_FLAGS = 4

    // What a command may start with without being the command: a change of directory, an environment variable, a wrapper. The summary has its
    // lines joined by spaces, so a `cd dir` or `export A=b` followed by more words may be a line of its own.
    private val PREFIXES = listOf(
        Regex("""^(cd|pushd|Set-Location)\s+(-\w+\s+)*("[^"]+"|'[^']+'|[^\s;&|"']+)\s+(?=[^\s;&|])"""),
        Regex("""^(export|set|declare|unset)\s+[^\s;&|]+\s+(?=[^\s;&|])"""),
        Regex("""^\${'$'}env:\w+\s*=\s*("[^"]*"|'[^']*'|[^;\s]*)\s*(;|&&)?\s*"""),
        Regex("""^[A-Za-z_][A-Za-z0-9_]*=("[^"]*"|'[^']*'|[^\s"']\S*|)\s+"""),
        Regex("""^timeout\s+\d+\w?\s+"""),
        Regex("""^(time|sudo|nohup|command|builtin)\s+"""),
    )

    // Commands that set the scene or print a heading: the command is the next one of the chain, if there is one.
    private val SCENE = setOf("cd", "pushd", "popd", "export", "unset", "set", "declare", "source", ".", "echo", "printf", "true", ":", "Set-Location")
    private val ASSIGNMENT_START = Regex("""^[A-Za-z_][A-Za-z0-9_]*=""")
    private val LOOPS = setOf("for", "while", "until")
    private val CONDITIONALS = setOf("if", "case", "select")

    // `<<` and `<<-` with a word (quoted or not), but not the here-string `<<<` nor a shift `1 << 2`.
    private val HEREDOC = Regex("""(?<!<)<<-?\s*(["']?)[A-Za-z_][A-Za-z0-9_]*\1""")
    private val HERE_STRING = Regex("""(^|[\s=(])@["']\s""")
    private val REDIRECT_HEAD = Regex("""(^|\s)\d?>>?\s*[^\s&|]|\|\s*tee\b""")
    private val REDIRECT_TAIL = Regex("""^\s*>>?\s*\S""")
    private val POWERSHELL_WRITE = Regex("""Set-Content|Add-Content|Out-File|WriteAllText""")
    private val TOKEN = Regex("""(?:"(?:[^"\\]|\\.)*"|'[^']*'|[^\s"'])+|["'].*""")
    private val REDIRECT_ALONE = Regex("""^\d?[<>]{1,2}$""")
    private val REDIRECT_JOINED = Regex("""^(\d?>>?|&>|<)\S*$""")
    private val PROGRAM_DIR = Regex("""^.*[\\/]""")
    private val PROGRAM_EXT = Regex("""\.(exe|cmd|bat)$""", RegexOption.IGNORE_CASE)
    private val WORD = Regex("""^[A-Za-z][A-Za-z0-9_-]{0,30}$""")
    private val VERB = Regex("""^[a-z][a-z-]{0,20}$""")
    private val TASK = Regex("""^:?[A-Za-z][\w:.-]{0,60}$""")
    private val NUMBER = Regex("""^[+-]?\d[\d,.:-]*[A-Za-z]?$""")
    private val PATH = Regex("""[\\/]|^~|^\.{1,2}$|^[\w.@-]+\.[A-Za-z0-9]{1,6}$|[*?]""")
    private val SCRIPT = Regex("""\.(mjs|cjs|js|ts|py|sh|ps1|rb|pl)$""", RegexOption.IGNORE_CASE)
    private val INTERPRETERS = setOf("node", "python", "bash", "sh", "zsh", "pwsh", "powershell", "deno", "bun", "ruby", "perl")
    private val INLINE_FLAGS = setOf("-e", "-c", "-p", "-command", "-encodedcommand", "-eval")
    private val ALIASES = mapOf("python3" to "python", "py" to "python")

    /** Programs whose first word is a subcommand, with the first words that take a second one (`git diff`, `docker compose up`). */
    private val SUBCOMMANDS = setOf(
        "git", "gh", "docker", "docker-compose", "npm", "npx", "pnpm", "yarn", "bun", "cargo", "kubectl", "dotnet", "go", "pip", "pip3", "uv",
        "brew", "winget", "mvn", "mvnw", "codeloupe", "make", "gradle", "gradlew", "adb", "systemctl",
    )
    private val GROUPS = mapOf(
        "codeloupe" to setOf("metrics", "ws", "job", "webhook", "repos", "know", "env"),
        "gh" to setOf("pr", "run", "issue", "repo", "release", "workflow"),
        "docker" to setOf("compose", "system", "container", "image", "network", "volume", "builder"),
    )

    /** Options that take a value and come before the subcommand: dropped with their value. */
    private val GLOBAL_VALUE_FLAGS = mapOf(
        "git" to setOf("-C", "-c", "--git-dir", "--work-tree"), "codeloupe" to setOf("--root"), "docker" to setOf("-H", "--host", "--context"),
        "kubectl" to setOf("-n", "--namespace", "--context"), "gradle" to setOf("-p", "--project-dir"), "gradlew" to setOf("-p", "--project-dir"),
    )
    private val GLOBAL_PLAIN_FLAGS = setOf("--no-pager", "-P", "--no-optional-locks", "--paginate")

    fun of(command: String?): String {
        val text = command.orEmpty().trim()
        if (text.isEmpty()) return EMPTY
        heredoc(text)?.let { return it }
        val command = commandOf(text) ?: return EMPTY
        // The summary is cut at 200 characters: a line that is still setting the scene there does not show its command.
        if (text.endsWith("…") && setsScene(command)) return CUT
        return label(tokens(command))
    }

    private fun tokens(text: String): List<String> = TOKEN.findAll(text).map { it.value }.toList()

    private fun strip(command: String): String {
        var c = command.trim()
        while (true) {
            val next = PREFIXES.fold(c) { text, rule -> text.replaceFirst(rule, "") }
            if (next == c) return c
            c = next
        }
    }

    /** The command of a line: the first of its chain that is not a `cd`, `export` or `echo`, else the first; null when nothing is left. */
    private fun commandOf(text: String): String? {
        val parts = segments(text).map(::strip).filter { it.isNotEmpty() }
        return parts.firstOrNull { !setsScene(it) } ?: parts.firstOrNull()
    }

    private fun setsScene(command: String): Boolean = ASSIGNMENT_START.containsMatchIn(command) || program(tokens(command).firstOrNull()) in SCENE

    private fun heredoc(command: String): String? {
        val at = HEREDOC.find(command) ?: HERE_STRING.find(command) ?: return null
        val head = command.substring(0, at.range.first).trim()
        val tokens = tokens(commandOf(head.ifEmpty { command }).orEmpty())
        val program = program(tokens.firstOrNull())
        val writes = program == "cat" || program == "tee" || REDIRECT_HEAD.containsMatchIn(head) ||
            REDIRECT_TAIL.containsMatchIn(command.substring(at.range.last + 1)) || POWERSHELL_WRITE.containsMatchIn(command)
        if (writes) return HEREDOC_PREFIX + "write"
        val words = label(tokens).split(' ').take(if (program in SUBCOMMANDS) 2 else 1)
        return HEREDOC_PREFIX + words.joinToString(" ").ifEmpty { "shell" }
    }

    /** The parts of a chain or pipe: the text between `&&`, `||`, `;`, `|` and `&` that are not inside quotes. */
    private fun segments(text: String): List<String> {
        val parts = ArrayList<String>()
        var quote = ' '
        var from = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quote != ' ' -> if (ch == '\\' && quote == '"') i++ else if (ch == quote) quote = ' '
                ch == '"' || ch == '\'' -> quote = ch
                ch == ';' || ch == '|' || (ch == '&' && text.getOrNull(i - 1) != '>' && text.getOrNull(i - 1) != '<' && text.getOrNull(i + 1) != '>') -> {
                    parts += text.substring(from, i)
                    from = i + 1
                }
            }
            i++
        }
        parts += text.substring(from)
        return parts
    }

    private fun label(all: List<String>): String {
        val tokens = withoutRedirects(all)
        val program = program(tokens.firstOrNull()).ifEmpty { return EMPTY }
        if (program in LOOPS) return "shell loop"
        if (program in CONDITIONALS) return "shell conditional"
        val rest = ArrayDeque(tokens.drop(1))
        dropGlobals(program, rest)
        val parts = arrayListOf(program)
        val flags = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        val placeholders = linkedSetOf<String>()
        var budget = if (program in SUBCOMMANDS) 1 else 0
        var taken = 0
        var needScript = program in INTERPRETERS && rest.none { it.lowercase() in INLINE_FLAGS }
        for (t in rest) {
            val flag = flagName(t)
            val bare = t.trim('"', '\'')
            when {
                flag != null -> if (flag.isNotEmpty()) flags += flag
                needScript -> {
                    needScript = false
                    if (SCRIPT.containsMatchIn(bare)) {
                        parts += program(bare)
                        budget = 2
                    } else placeholders += placeholder(t)
                }
                budget > 0 && isSubcommand(program, t, taken) -> {
                    parts += t
                    budget = if (taken == 0 && t in GROUPS[program].orEmpty()) 1 else budget - 1
                    taken++
                }
                else -> placeholders += placeholder(t)
            }
        }
        val shown = flags.toList()
        parts += shown.take(MAX_FLAGS)
        if (shown.size > MAX_FLAGS) parts += "…"
        parts += placeholders
        return parts.joinToString(" ")
    }

    // A second word is a subcommand only when it is a plain lowercase verb: a task id or a name there is an argument.
    private fun isSubcommand(program: String, word: String, taken: Int): Boolean = when {
        program == "gradle" || program == "gradlew" -> TASK.matches(word)
        taken > 0 -> VERB.matches(word)
        else -> WORD.matches(word)
    }

    private fun withoutRedirects(tokens: List<String>): List<String> {
        val out = ArrayList<String>()
        var skip = false
        for (t in tokens) {
            when {
                skip -> skip = false
                REDIRECT_ALONE.matches(t) -> skip = true
                REDIRECT_JOINED.matches(t) -> Unit
                else -> out += t
            }
        }
        return out
    }

    private fun dropGlobals(program: String, rest: ArrayDeque<String>) {
        val withValue = GLOBAL_VALUE_FLAGS[program].orEmpty()
        while (rest.isNotEmpty()) {
            val t = rest.first()
            val name = t.substringBefore('=')
            when {
                program == "git" && t in GLOBAL_PLAIN_FLAGS -> rest.removeFirst()
                name in withValue -> {
                    rest.removeFirst()
                    if (name == t && rest.isNotEmpty()) rest.removeFirst()
                }
                else -> return
            }
        }
    }

    /** The flag a token is, without its value; null for anything that is not one, empty for the bare `-` and `--`. */
    private fun flagName(token: String): String? {
        if (!token.startsWith("-")) return null
        if (token == "-" || token == "--") return ""
        if (NUMBER.matches(token)) return "-<n>"
        return token.substringBefore('=').substringBefore(':').ifEmpty { token }
    }

    private fun placeholder(token: String): String = when {
        token.startsWith("\"") || token.startsWith("'") -> "<str>"
        NUMBER.matches(token) -> "<n>"
        PATH.containsMatchIn(token) -> "<path>"
        else -> "<arg>"
    }

    private fun program(token: String?): String {
        val name = token.orEmpty().trim('"', '\'').replaceFirst(PROGRAM_DIR, "").replaceFirst(PROGRAM_EXT, "")
        return ALIASES[name] ?: name
    }
}
