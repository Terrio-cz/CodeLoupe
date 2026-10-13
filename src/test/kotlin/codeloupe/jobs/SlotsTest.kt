package codeloupe.jobs

import codeloupe.config.JobsConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlotsTest {
    private val t0 = Instant.parse("2026-10-13T10:00:00Z")

    @Test
    fun `a raised capacity hands the freed place to the first waiter at once, a cut takes nobody out`() = runBlocking {
        val slots = Slots(mapOf("g" to 1))
        val tickets = (1..4).map { slots.enter("g", "J$it") }
        assertEquals(listOf(true, false, false, false), tickets.map { it.atOnce })
        slots.configure(mapOf("g" to 2))
        assertTrue(tickets[1].turn.isCompleted, "the first waiter got the new place")
        assertFalse(tickets[2].turn.isCompleted)
        assertEquals(listOf("J1", "J2"), slots.snapshot().single().running)

        slots.configure(emptyMap())
        val cut = slots.snapshot().single()
        assertEquals(1, cut.capacity)
        assertEquals(listOf("J1", "J2"), cut.running, "a cut ends nobody")
        slots.release("g", "J1")
        assertFalse(tickets[2].turn.isCompleted, "still one holder too many for capacity 1")
        slots.release("g", "J2")
        assertTrue(tickets[2].turn.isCompleted)
        assertFalse(tickets[3].turn.isCompleted)
    }

    @Test
    fun `move reorders the waiting jobs of one slot and refuses holders, strangers and other slots`() {
        val slots = Slots()
        (1..4).forEach { slots.enter("g", "J$it") }
        slots.enter("other", "K1")
        slots.enter("other", "K2")
        assertEquals("g" to listOf("J4", "J2", "J3"), slots.move("J4", QueuePosition.First))
        assertEquals(listOf("J4", "J3", "J2"), slots.move("J2", QueuePosition.After("J3")).second)
        assertEquals(listOf("J3", "J4", "J2"), slots.move("J3", QueuePosition.Before("J4")).second)
        assertContains(assertFailsWith<IllegalArgumentException> { slots.move("J1", QueuePosition.First) }.message!!, "is running")
        assertContains(assertFailsWith<IllegalArgumentException> { slots.move("J9", QueuePosition.First) }.message!!, "does not wait")
        assertContains(assertFailsWith<IllegalArgumentException> { slots.move("J2", QueuePosition.Before("J1")) }.message!!, "(it runs)")
        assertContains(assertFailsWith<IllegalArgumentException> { slots.move("J2", QueuePosition.After("K2")) }.message!!, "does not wait in slot g")
        assertFailsWith<IllegalArgumentException> { slots.move("J2", QueuePosition.After("J2")) }
        assertEquals(listOf("J3", "J4", "J2"), slots.queues().getValue("g"), "a refused move changes nothing")
        slots.release("g", "J1")
        assertEquals(listOf("J3"), slots.snapshot().first { it.name == "g" }.running, "the head of the new order goes first")
    }

    @Test
    fun `the snapshot shows where each capacity comes from and how long each job has held or waited`() {
        val clock = MutableClock(t0)
        val slots = Slots(clock = clock)
        val config = JobsConfig.parse(Json.parseToJsonElement("""{"slots": {"g": 2, "bad": "two", "zero": 0}}""").jsonObject)
        slots.configure(config.slots, config.invalidSlots)
        slots.enter("g", "J1")
        clock.now = t0.plusSeconds(60)
        slots.enter("g", "J2")
        slots.enter("g", "J3", since = t0.plusSeconds(30))
        clock.now = t0.plusSeconds(600)
        val byName = slots.snapshot().associateBy { it.name }
        assertEquals(setOf("bad", "g", "zero"), byName.keys)
        val g = byName.getValue("g")
        assertEquals(2, g.configured)
        assertEquals(listOf(SlotEntry("J1", t0.toString(), 600), SlotEntry("J2", t0.plusSeconds(60).toString(), 540)), g.holders)
        assertEquals(listOf(SlotEntry("J3", t0.plusSeconds(30).toString(), 570)), g.queue, "waiting since its submission")
        assertEquals("\"two\"", byName.getValue("bad").invalid)
        assertEquals(1, byName.getValue("bad").capacity)
        assertEquals("0", byName.getValue("zero").invalid)
        val text = SlotReport.text(slots.snapshot())
        assertContains(text, "g: 2 of 2 (config.json) · holding J1 10 min 0 s, J2 9 min 0 s · waiting 1: J3 9 min 30 s")
        assertContains(text, "bad: 0 of 1 (config.json has \"two\", not a positive whole number: 1)")
    }

    @Test
    fun `a holder that is cancelled passes its place on`() = runBlocking {
        val slots = Slots()
        slots.enter("g", "J1")
        val second = slots.enter("g", "J2")
        val third = slots.enter("g", "J3")
        val holder = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { slots.hold(second, {}) { awaitCancellation() } }
        slots.release("g", "J1")
        holder.cancel()
        withTimeout(5_000) { third.turn.await() }
        assertEquals(listOf("J3"), slots.snapshot().single().running)
    }

    @Test
    fun `a job a restarted daemon found running holds its slot beyond the capacity`() {
        val slots = Slots(mapOf("g" to 1))
        slots.adopt("g", "J0", t0)
        val next = slots.enter("g", "J1")
        assertFalse(next.atOnce)
        slots.release("g", "J0")
        assertTrue(next.turn.isCompleted)
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now
    }
}
