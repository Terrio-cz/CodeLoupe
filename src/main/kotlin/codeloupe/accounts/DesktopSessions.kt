package codeloupe.accounts

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Which account ran a Claude Code session, as the Claude Desktop instance that started it says (CL-231): an instance keeps one
 * `local_*.json` per session under `<desktopDir>/claude-code-sessions`, and its `cliSessionId` is the id of the Claude Code session.
 * Nothing else of those files is looked at: only the start of a file is read, only the id is cut from it, and nothing of the rest is
 * kept or logged (the files also hold prompts and titles). A file that is damaged, or names no id, attributes nothing. A file is read
 * again only when its size or time changes, since sessions keep arriving. A session two accounts claim goes to the one whose file is
 * newer, and to none when both are equally new.
 */
class DesktopSessions {
    private class Entry(val size: Long, val mtime: Long, val session: String?)

    private val cache = HashMap<Path, Entry>()

    /** Session id to the id of the account whose [ClaudeAccount.desktopDir] lists it. */
    @Synchronized
    fun sessions(accounts: List<ClaudeAccount>): Map<String, String> {
        val seen = HashSet<Path>()
        val owner = HashMap<String, Pair<String, Long>>()
        val tied = HashSet<String>()
        for (account in accounts) {
            val dir = account.desktopDir?.resolve(FOLDER) ?: continue
            for ((file, attrs) in listing(dir)) {
                seen.add(file)
                val session = entry(file, attrs).session ?: continue
                val mtime = attrs.lastModifiedTime().toMillis()
                val current = owner[session]
                when {
                    current == null || mtime > current.second -> { owner[session] = account.id to mtime; tied.remove(session) }
                    mtime == current.second && current.first != account.id -> tied.add(session)
                }
            }
        }
        cache.keys.retainAll(seen)
        return owner.filterKeys { it !in tied }.mapValues { it.value.first }
    }

    private fun entry(file: Path, attrs: BasicFileAttributes): Entry {
        val size = attrs.size()
        val mtime = attrs.lastModifiedTime().toMillis()
        cache[file]?.takeIf { it.size == size && it.mtime == mtime }?.let { return it }
        return Entry(size, mtime, sessionOf(file)).also { cache[file] = it }
    }

    private fun sessionOf(file: Path): String? = runCatching {
        val head = Files.newInputStream(file).use { it.readNBytes(HEAD_BYTES) }.toString(Charsets.UTF_8)
        CLI_SESSION.find(head)?.groupValues?.get(1)
    }.getOrNull()

    private fun listing(dir: Path): List<Pair<Path, BasicFileAttributes>> {
        if (!Files.isDirectory(dir)) return emptyList()
        val found = ArrayList<Pair<Path, BasicFileAttributes>>()
        runCatching {
            Files.walkFileTree(
                dir, emptySet(), MAX_DEPTH,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = file.fileName.toString()
                        if (attrs.isRegularFile && name.startsWith("local_") && name.endsWith(".json")) found += file to attrs
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        }
        return found
    }

    private companion object {
        const val FOLDER = "claude-code-sessions"
        const val MAX_DEPTH = 6

        /** `sessionId` and `cliSessionId` are the first keys of a file; the bytes after the head are never read. */
        const val HEAD_BYTES = 4096
        val CLI_SESSION = Regex("\"cliSessionId\"\\s*:\\s*\"([0-9A-Za-z_-]{1,100})\"")
    }
}
