package codeloupe.events

import codeloupe.TestRepos
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EventSeqRestartTest {
    @Test
    fun `event numbers and the epoch survive a restart, a deleted log starts a new epoch`() {
        val file = TestRepos.tmpDir("event-seq").resolve("events.db")
        val epoch: String
        EventStore(file).use { store ->
            epoch = store.epoch()
            assertEquals(1, store.append(EventTypes.BUDGET_BREACH, "2026-10-08T10:00:00.000Z", buildJsonObject { put("scope", "day") }).seq)
            assertEquals(2, store.append(EventTypes.GAP_NEW, "2026-10-08T10:01:00.000Z", buildJsonObject { put("tool", "find") }).seq)
        }
        EventStore(file).use { store ->
            assertEquals(epoch, store.epoch())
            assertEquals(2, store.lastSeq())
            assertEquals(3, store.append(EventTypes.BUDGET_BREACH, "2026-10-09T10:00:00.000Z", buildJsonObject { put("scope", "run") }).seq)
            assertEquals(listOf(EventTypes.GAP_NEW, EventTypes.BUDGET_BREACH), store.since(1, 10).map { it.type })
        }
        file.parent.toFile().listFiles()!!.forEach { it.delete() }
        EventStore(file).use { store ->
            assertNotEquals(epoch, store.epoch(), "a new log is a new epoch, so the app does not mistake its numbers for the old ones")
            assertEquals(1, store.append(EventTypes.GAP_NEW, "2026-10-10T10:00:00.000Z", buildJsonObject { put("tool", "find") }).seq)
        }
    }
}
