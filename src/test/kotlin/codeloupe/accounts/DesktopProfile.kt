package codeloupe.accounts

import codeloupe.TestRepos
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** A constructed user-data directory of a Claude Desktop instance: `claude-code-sessions/<org>/<user>/local_<id>.json` files. */
class DesktopProfile(val dir: Path = TestRepos.tmpDir("desktop-profile")) {
    private val folder = dir.resolve("claude-code-sessions").resolve("org-1").resolve("user-1")

    /** A session file as the desktop app writes it: the ids first, then the conversation-bound fields that must never be read. */
    fun session(cliSessionId: String, name: String = "local_$cliSessionId", mtimeMs: Long? = null): Path {
        val body = """{"sessionId":"$name","cliSessionId":"$cliSessionId","cwd":"C:/work","title":"never read this title","prompt":"secret-prompt-text","oauth":"fake-token-not-for-us"}"""
        return write("$name.json", body, mtimeMs)
    }

    fun write(file: String, body: String, mtimeMs: Long? = null): Path {
        Files.createDirectories(folder)
        return folder.resolve(file).also {
            Files.writeString(it, body)
            mtimeMs?.let { ms -> Files.setLastModifiedTime(it, FileTime.fromMillis(ms)) }
        }
    }

    fun account(id: String, configDir: Path = dir.resolveSibling("cfg-$id")) = ClaudeAccount(id, id, configDir, implicit = false, desktopDir = dir)
}
