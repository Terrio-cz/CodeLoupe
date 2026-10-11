package codeloupe.ingest

import codeloupe.accounts.AccountResolver
import java.sql.Connection

/**
 * Brings the account columns of what is already stored in line with what is known now (CL-226): a session stamped after its transcript
 * was read gets its identity, and an account that shares a directory (or an `identity-<fingerprint>` that `accounts.json` has since
 * claimed) is resolved again. It runs only when a stamp is new or the listed accounts changed, and a failure is retried by the next pass.
 */
internal object AccountBackfill {
    /** What the last run saw: the stamps it applied and the accounts it resolved with. */
    class Done(val stamps: Map<String, String> = emptyMap(), val accounts: String? = null)

    private val TABLES = listOf("files", "usage_snapshots")

    fun run(db: TranscriptDb, stamps: Map<String, String>, resolver: AccountResolver, done: Done, log: (String) -> Unit): Done {
        val fresh = stamps.filter { done.stamps[it.key] != it.value }
        if (fresh.isEmpty() && done.accounts == resolver.signature()) return done
        return try {
            db.transaction { c ->
                for (table in TABLES) {
                    stamp(c, table, fresh)
                    resolve(c, table, resolver)
                }
            }
            Done(stamps, resolver.signature())
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
