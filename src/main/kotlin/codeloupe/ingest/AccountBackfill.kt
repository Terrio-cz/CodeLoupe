package codeloupe.ingest

import codeloupe.accounts.AccountResolver
import java.sql.Connection

/**
 * Brings the account columns of what is already stored in line with what is known now (CL-226): a session stamped after its transcript
 * was read gets its identity, and an account that shares a directory (or an `identity-<fingerprint>` that `accounts.json` has since
 * claimed) is resolved again. A session that a desktop instance lists (CL-231) goes to that instance's account, over everything else.
 * It runs only when a stamp or a desktop session is new or the listed accounts changed, and a failure is retried by the next pass.
 */
internal object AccountBackfill {
    /** What the last run saw: the stamps it applied, the accounts it resolved with and the desktop sessions it assigned. */
    class Done(val stamps: Map<String, String> = emptyMap(), val accounts: String? = null, val desktop: Map<String, String> = emptyMap())

    private val TABLES = listOf("files", "usage_snapshots")

    fun run(db: TranscriptDb, stamps: Map<String, String>, resolver: AccountResolver, done: Done, log: (String) -> Unit): Done {
        val fresh = stamps.filter { done.stamps[it.key] != it.value }
        val changed = done.accounts != resolver.signature()
        val freshDesktop = resolver.desktop.filter { done.desktop[it.key] != it.value }
        if (fresh.isEmpty() && freshDesktop.isEmpty() && !changed) return done
        // Resolving by identity may undo a desktop assignment, so after it the whole desktop map is applied again.
        val identityRun = fresh.isNotEmpty() || changed
        return try {
            db.transaction { c ->
                for (table in TABLES) {
                    stamp(c, table, fresh)
                    if (identityRun) resolve(c, table, resolver)
                    assign(c, table, if (identityRun) resolver.desktop else freshDesktop)
                }
            }
            Done(stamps, resolver.signature(), resolver.desktop)
        } catch (e: Exception) {
            log("account backfill: ${e.message}")
            done
        }
    }

    private fun stamp(c: Connection, table: String, fresh: Map<String, String>) {
        if (fresh.isEmpty()) return
        c.prepareStatement("UPDATE $table SET identity = ? WHERE session = ? AND identity IS NULL").use { s ->
            for ((session, identity) in fresh) {
                s.setString(1, identity)
                s.setString(2, session)
                s.addBatch()
            }
            s.executeBatch()
        }
    }

    private fun assign(c: Connection, table: String, sessions: Map<String, String>) {
        if (sessions.isEmpty()) return
        c.prepareStatement("UPDATE $table SET account = ? WHERE session = ? AND account IS NOT ?").use { s ->
            for ((session, account) in sessions) {
                s.setString(1, account)
                s.setString(2, session)
                s.setString(3, account)
                s.addBatch()
            }
            s.executeBatch()
        }
    }

    private fun resolve(c: Connection, table: String, resolver: AccountResolver) {
        val pairs = c.createStatement().use { s ->
            s.executeQuery("SELECT DISTINCT identity, account FROM $table WHERE identity IS NOT NULL").use { r -> buildList { while (r.next()) add(r.getString(1) to r.getString(2)) } }
        }
        c.prepareStatement("UPDATE $table SET account = ? WHERE identity = ? AND account IS ?").use { s ->
            for ((identity, account) in pairs) {
                val target = resolver.resolve(account, identity)
                if (target == account) continue
                s.setString(1, target)
                s.setString(2, identity)
                s.setString(3, account)
                s.addBatch()
            }
            s.executeBatch()
        }
    }
}
