package codeloupe.metrics

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class InputSizeTest {
    @Test
    fun `a call is the characters of its names and values, not of the JSON they travel in`() {
        val input = buildJsonObject {
            put("file_path", "a.kt")
            put("content", "x\n\"y\"")
        }
        assertEquals(9 + 4 + 7 + 5, InputSize.chars(input), "the newline and the quotes count once, the braces and escapes not at all")
    }

    @Test
    fun `numbers, flags, lists and nested objects count by what they hold`() {
        val input = buildJsonObject {
            put("limit", 200)
            put("replace_all", true)
            put("edits", buildJsonArray {
                add(buildJsonObject { put("old", "ab"); put("new", "cde") })
            })
            putJsonObject("more") { put("k", "v") }
        }
        assertEquals((5 + 3) + (11 + 4) + (5 + (3 + 2 + 3 + 3)) + (4 + (1 + 1)), InputSize.chars(input))
    }

    @Test
    fun `a call without input sent nothing`() {
        assertEquals(0, InputSize.chars(buildJsonObject {}))
    }
}
