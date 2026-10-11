package codeloupe.hooks

import codeloupe.accounts.ClaudeLogin
import codeloupe.accounts.SessionIdentities
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Notes which login runs a session when it starts (CL-226): the fingerprint of the account in the session's config directory goes to
 * [SessionIdentities], and the ingest gives it to the session's transcripts. [note] only queues the work, so a hook never waits for the
 * disk, and every failure is swallowed. A subagent belongs to the session that started it, so its start adds nothing for a session already
 * stamped; it only covers a session that began before the daemon was running.
 */
class SessionStamps(
    private val store: SessionIdentities,
    private val login: ClaudeLogin,
    private val run: (Runnable) -> Unit = background(),
) {
    private val stamped = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > MAX_SESSIONS
    }

    fun note(event: String, session: String, transcriptPath: String?) {
        if (!SESSION_ID.matches(session)) return
        if (event == SUBAGENT_START && synchronized(stamped) { session in stamped }) return
        val dir = transcriptPath?.let(SessionConfigDir::of) ?: return
        runCatching {
            run(
                Runnable {
                    runCatching {
                        val fingerprint = login.fingerprintOf(dir) ?: return@runCatching
                        if (synchronized(stamped) { stamped.put(session, fingerprint) } != fingerprint) store.record(session, fingerprint)
                    }
                },
            )
        }
    }

    companion object {
        const val SESSION_START = "SessionStart"
        const val SUBAGENT_START = "SubagentStart"
        private const val MAX_SESSIONS = 1_000
        private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,100}")

        /** One daemon thread with a short queue: when it is full the stamp is dropped, the next start of the session tries again. */
        fun background(): (Runnable) -> Unit {
            val pool = ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, ArrayBlockingQueue(64), { task -> Thread(task, "session-stamps").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardPolicy())
            pool.allowCoreThreadTimeOut(true)
            return pool::execute
        }
    }
}
