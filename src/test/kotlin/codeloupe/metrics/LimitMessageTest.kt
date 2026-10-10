package codeloupe.metrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LimitMessageTest {
    private val prague = ZoneId.of("Europe/Prague")
    private val at = Instant.parse("2026-10-08T21:07:41Z")

    private fun hit(text: String, line: String = "{}", model: String? = "<synthetic>"): LimitHit? {
        val message = model?.let { Json.parseToJsonElement("{\"model\":\"$it\"}") as JsonObject }
        return LimitMessage.of(Json.parseToJsonElement(line) as JsonObject, message, text, at.toEpochMilli())
    }

    private fun prague(text: String): ZonedDateTime? =
        LimitMessage.resetsAt(text, at.toEpochMilli())?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), prague) }

    @Test
    fun `the quota the line carries sets the reset time and the type names the limit when the text does not`() {
        val line = "{\"error\":\"rate_limit\",\"quotaLimits\":{\"status\":\"rejected\",\"resetsAt\":1791741600,\"rateLimitType\":\"seven_day\"}}"
        assertEquals(LimitHit(at.toEpochMilli(), 1_791_741_600_000, "weekly", "You've hit your limit"), hit("You've hit your limit", line))
        assertEquals("opus", hit("You've hit your Opus limit · resets 3pm (Europe/Prague)", line)?.kind)
        assertEquals(1_791_741_600_000, hit("You've hit your weekly limit · resets Oct 11, 8pm (Europe/Prague)", line)?.resetsAtMs)
    }

    @Test
    fun `a reset time in the text is read in the zone it names, the next time it comes around`() {
        assertEquals(ZonedDateTime.of(2026, 10, 9, 20, 0, 0, 0, prague), prague("You've hit your session limit · resets 8pm (Europe/Prague)"), "21:07 UTC is 23:07 in Prague: 8pm is tomorrow")
        assertEquals(ZonedDateTime.of(2026, 10, 9, 4, 40, 0, 0, prague), prague("resets 4:40am (Europe/Prague)"))
        assertEquals(ZonedDateTime.of(2026, 10, 11, 20, 0, 0, 0, prague), prague("You've hit your weekly limit · resets Oct 11, 8pm (Europe/Prague)"))
        assertEquals(ZonedDateTime.of(2027, 1, 2, 0, 0, 0, 0, prague), prague("resets Jan 2, 12am (Europe/Prague)"))
        assertNull(LimitMessage.resetsAt("You've hit your limit", at.toEpochMilli()))
        assertNull(LimitMessage.resetsAt("resets 8pm (Nowhere/Land)", at.toEpochMilli()))
    }

    @Test
    fun `the kind comes from the text`() {
        assertEquals("session", hit("You've hit your session limit")?.kind)
        assertEquals("weekly", hit("You've hit your weekly limit · resets Oct 11, 8pm (Europe/Prague)")?.kind)
        assertEquals("sonnet", hit("You've hit your Sonnet limit")?.kind)
        assertEquals("spend", hit("You've hit your individual spend limit")?.kind)
        assertEquals("limit", hit("You've hit your limit")?.kind)
    }

    @Test
    fun `a report that mentions the phrase, or a plain reply, is no limit answer`() {
        assertNull(hit("You've hit your weekly limit " + "x".repeat(400), "{\"error\":\"rate_limit\"}"))
        assertNull(hit("Here is what to do when you've hit your weekly limit", "{\"error\":\"rate_limit\"}"))
        assertNull(hit("You've hit your weekly limit", model = "claude-opus"), "a real model wrote it and nothing flagged it")
        assertNull(hit("fine", "{\"error\":\"rate_limit\"}"))
    }
}
