package codeloupe.hooks

import codeloupe.accounts.ClaudeAccount
import codeloupe.accounts.ClaudeLogin
import codeloupe.accounts.SessionIdentities
import codeloupe.metrics.AccountIdentity
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Notes which login runs a session when it starts (CL-226): its fingerprint goes to [SessionIdentities], and the ingest gives it to the
 * session's transcripts. The session's own login decides, as the hook saw it in the session's environment ([SessionLogin]): the account
 * uuid Claude Desktop hands its sessions, else the account a launcher named, mapped to the identity `accounts.json` declares for it.
 * Only a session with neither and without a token of its own is given the login file of its config directory: that is the login the
 * CLI itself signs in with, while a desktop or token session may run as another one than the file names. [note] only queues
 * the work, so a hook never waits for the disk, and every failure is swallowed. A subagent belongs to the session that started it, so its
 * start adds nothing for a session already stamped; it only covers a session that began before the daemon was running.
 */
class SessionStamps(
    private val store: SessionIdentities,
    private val login: ClaudeLogin,
    private val accounts: () -> List<ClaudeAccount> = { emptyList() },
    private val run: (Runnable) -> Unit = background(),
) {
    private val stamped = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > MAX_SESSIONS
    }

    fun note(event: String, session: String, transcriptPath: String?, own: SessionLogin = SessionLogin.NONE) {
        if (!SESSION_ID.matches(session)) return
        if (event == SUBAGENT_START && synchronized(stamped) { session in stamped }) return
        val dir = transcriptPath?.let(SessionConfigDir::of)
        if (dir == null && own.uuid == null && own.account == null) return
        runCatching {
            run(
                Runnable {
                    runCatching {
                        val fingerprint = fingerprintOf(own, dir) ?: return@runCatching
                        if (synchronized(stamped) { stamped.put(session, fingerprint) } != fingerprint) store.record(session, fingerprint)
                    }
                },
            )
        }
    }

    private fun fingerprintOf(own: SessionLogin, dir: Path?): String? {
        own.uuid?.let { return AccountIdentity.fingerprint(it) }
        if (own.account != null) {
            return accounts().filter { it.id == own.account || it.email.equals(own.account, ignoreCase = true) }.singleOrNull()?.identity
        }
        if (own.token || dir == null) return null
        return login.fingerprintOf(dir)
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
