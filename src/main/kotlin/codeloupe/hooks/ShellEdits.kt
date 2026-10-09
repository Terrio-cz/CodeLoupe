package codeloupe.hooks

import codeloupe.hooks.ShellCommandWords.program
import codeloupe.hooks.ShellCommandWords.unwrap

/**
 * Whether the commands after a search in its pipeline edit the files it found: `rg -l Foo | xargs sed -i …`. Such a
 * search lists the files to change; it is not a question the index answers.
 */
internal object ShellEdits {
    private const val MAX_DEPTH = 2
    private val WRITERS = setOf("sd", "set-content", "sc", "out-file", "add-content", "ac", "sponge")
    private val SHELLS = setOf("sh", "bash", "zsh", "dash")
    private val XARGS_VALUED = setOf("-I", "-n", "-P", "-L", "-s", "-E", "-a", "-d", "-R", "-S", "--max-args", "--max-procs", "--max-lines", "--delimiter", "--arg-file")

    /** True when a command piped (through any number of stages) after `commands[index]` edits files. */
    fun fedBy(commands: List<SimpleCommand>, index: Int): Boolean {
        var k = index + 1
        while (k < commands.size && commands[k].piped) {
            if (edits(commands[k].words)) return true
            k++
        }
        // `… | while read f; do sed -i … "$f"; done`: the body of the loop follows as commands of its own.
        val next = commands.getOrNull(index + 1)
        if (next == null || !next.piped || program(next.words.firstOrNull()) != "while") return false
        return commands.drop(index + 2).takeWhile { program(it.words.firstOrNull()) != "done" }.any { edits(it.words) }
    }

    private fun edits(words: List<String>, depth: Int = 0): Boolean {
        val command = unwrap(words)
        val rest = command.drop(1)
        return when (program(command.firstOrNull())) {
            in WRITERS -> true
            "sed" -> rest.any { it == "--in-place" || it.startsWith("--in-place=") || bundleHas(it, 'i', "nEersuz") }
            "perl", "ruby" -> rest.any { bundleHas(it, 'i', "0123456789pnalswWtTuU") }
            "awk", "gawk" -> rest.windowed(2).any { it[0] == "-i" && it[1] == "inplace" }
            "xargs" -> depth < MAX_DEPTH && edits(afterXargs(rest), depth + 1)
            in SHELLS -> depth < MAX_DEPTH && scripts(rest).any { script -> ShellWords.split(script).any { edits(it.words, depth + 1) } }
            else -> false
        }
    }

    /** A bundle of short options (`-pi`, `-i.bak`) in which [flag] comes before any option outside [before]. */
    private fun bundleHas(word: String, flag: Char, before: String): Boolean {
        if (word.length < 2 || word[0] != '-' || word[1] == '-') return false
        for (c in word.drop(1)) {
            if (c == flag) return true
            if (c !in before) return false
        }
        return false
    }

    private fun scripts(rest: List<String>): List<String> = rest.windowed(2).filter { it[0].startsWith("-") && !it[0].startsWith("--") && it[0].endsWith("c") }.map { it[1] }

    // The command `xargs` runs: what follows its own options, some of which take a value.
    private fun afterXargs(rest: List<String>): List<String> {
        var k = 0
        while (k < rest.size && rest[k].startsWith("-") && rest[k] != "-") k += if (rest[k] in XARGS_VALUED) 2 else 1
        return rest.drop(k)
    }
}
