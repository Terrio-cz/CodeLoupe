package codeloupe.hooks

import codeloupe.config.HooksConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostEditConfigTest {
    private fun parse(text: String) = HooksConfig.parse(Json.parseToJsonElement(text) as JsonObject)

    @Test
    fun `the check is off unless the settings turn it on`() {
        assertFalse(parse("{}").postEdit.enabled)
        assertFalse(parse("""{"hooks":{"postEdit":{"enabled":"yes"}}}""").postEdit.enabled)
        assertTrue(parse("""{"hooks":{"postEdit":{"enabled":true}}}""").postEdit.enabled)
    }

    @Test
    fun `the size of the answer and the time it may take stay within bounds`() {
        val parsed = parse("""{"hooks":{"postEdit":{"enabled":true,"maxLines":99,"timeoutMs":1}}}""").postEdit
        assertEquals(20, parsed.maxLines)
        assertEquals(50, parsed.timeoutMs)
        assertEquals(5, parse("{}").postEdit.maxLines)
        assertEquals(300, parse("{}").postEdit.timeoutMs)
    }
}
