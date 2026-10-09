package codeloupe.overlay

import kotlin.test.Test
import kotlin.test.assertEquals

class OverlaySourcesTest {
    private val known = mapOf("a.db" to setOf("src/A.kt", "src/B.kt"), "b.db" to setOf("src/C.kt"), "empty.db" to emptySet())

    @Test
    fun `a store is tried only when its known overlay lists one of the paths`() {
        val stores = listOf("a.db", "b.db", "empty.db", "new.db")
        assertEquals(listOf("a.db"), OverlaySources.relevant(stores, known, setOf("src/B.kt", "src/Z.kt")))
        assertEquals(emptyList(), OverlaySources.relevant(stores, known, setOf("src/Z.kt")))
        assertEquals(listOf("a.db", "b.db"), OverlaySources.relevant(stores, known, setOf("src/A.kt", "src/C.kt")))
    }
}
