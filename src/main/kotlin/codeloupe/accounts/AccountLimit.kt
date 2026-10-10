package codeloupe.accounts

import codeloupe.metrics.LimitHit
import java.time.Duration
import java.time.Instant

/**
 * The last known limit state of a Claude account, read from the newest usage-limit answer in its transcripts: `limited` while the
 * reset time the line named is ahead (an answer without one counts for [UNTIMED]), `ok` once it has passed, `unknown` when no
 * transcript of the account holds such an answer. [source] says where the knowledge comes from.
 */
data class AccountLimit(val state: String, val kind: String?, val resetsAt: Instant?, val observedAt: Instant?, val source: String) {
    companion object {
        const val SOURCE = "transcript"
        private val UNTIMED = Duration.ofMinutes(30)

        fun of(hit: LimitHit?, now: Instant): AccountLimit {
            if (hit == null) return AccountLimit("unknown", null, null, null, SOURCE)
            val observed = Instant.ofEpochMilli(hit.atMs)
            val resets = hit.resetsAtMs?.let(Instant::ofEpochMilli)
            val limited = if (resets != null) resets.isAfter(now) else observed.plus(UNTIMED).isAfter(now)
            return AccountLimit(if (limited) "limited" else "ok", hit.kind, resets, observed, SOURCE)
        }
    }
}
