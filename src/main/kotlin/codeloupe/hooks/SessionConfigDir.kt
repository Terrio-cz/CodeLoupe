package codeloupe.hooks

import java.nio.file.Path

/**
 * The Claude config directory a session runs under, from the `transcript_path` its hook names: transcripts lie in
 * `<configDir>/projects/<project>/<session>.jsonl` (a subagent's one folder level deeper per `<session>/subagents/`). It is read from the
 * text alone, because at `SessionStart` the file does not exist yet, and a network path is refused as in [TranscriptPath]. The daemon's own
 * `CLAUDE_CONFIG_DIR` is no substitute: it belongs to whichever session started the daemon.
 */
object SessionConfigDir {
    private const val MAX_LENGTH = 4096
    private const val PROJECTS = "projects"
    private const val LEVELS = 4

    fun of(transcriptPath: String): Path? {
        if (transcriptPath.length > MAX_LENGTH || transcriptPath.any { it.isISOControl() }) return null
        if (transcriptPath.replace('\\', '/').startsWith("//") || !transcriptPath.endsWith(".jsonl")) return null
        var dir = runCatching { Path.of(transcriptPath) }.getOrNull()?.takeIf { it.isAbsolute }?.parent
        repeat(LEVELS) {
            if (dir?.fileName?.toString() == PROJECTS) return dir.parent
            dir = dir?.parent
        }
        return null
    }
}
