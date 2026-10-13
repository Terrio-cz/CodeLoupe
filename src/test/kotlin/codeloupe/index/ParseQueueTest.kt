package codeloupe.index

import kotlin.test.Test
import kotlin.test.assertEquals

class ParseQueueTest {
    private fun file(name: String, chars: Int = 10) = ParseFile("src/$name.kt", "class $name\n" + " ".repeat(chars))

    @Test
    fun `steps run in the order they were queued, parses and other steps alike`() {
        val done = ArrayList<String>()
        val queue = ParseQueue()
        queue.then { done += "first, nothing waits" }
        queue.parse(file("A")) { done += "A ${it.decls.single().name}" }
        queue.then { done += "after A" }
        queue.parse(file("B")) { done += "B ${it.decls.single().name}" }
        assertEquals(listOf("first, nothing waits"), done, "the rest waits for the batch")
        queue.flush()
        assertEquals(listOf("first, nothing waits", "A A", "after A", "B B"), done)
    }

    @Test
    fun `a batch is parsed when the next file would make it hold more text or steps than allowed`() {
        val done = ArrayList<String>()
        val queue = ParseQueue(maxChars = 100, maxSteps = 3)
        queue.parse(file("A", 40)) { done += "A" }
        queue.parse(file("B", 40)) { done += "B" }
        queue.parse(file("C", 40)) { done += "C" }
        assertEquals(listOf("A", "B"), done, "C would pass 100 chars")
        queue.parse(file("D", 1)) { done += "D" }
        queue.parse(file("E", 1)) { done += "E" }
        queue.parse(file("F", 1)) { done += "F" }
        assertEquals(listOf("A", "B", "C", "D", "E"), done, "three steps at most")
        queue.parse(file("Big", 500)) { done += "Big" }
        queue.flush()
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "Big"), done, "a file over the limit goes alone")
    }

    @Test
    fun `what a step queues runs before the next step of its batch`() {
        val done = ArrayList<String>()
        val queue = ParseQueue()
        queue.parse(file("A")) {
            done += "A"
            queue.parse(file("Fallback")) { done += "Fallback" }
        }
        queue.parse(file("B")) { done += "B" }
        queue.flush()
        assertEquals(listOf("A", "Fallback", "B"), done)
    }
}
