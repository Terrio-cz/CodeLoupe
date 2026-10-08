package codeloupe.docker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeOverrideTest {
    private val owner = Ownership("Terrio", "TER-12", "TER-12")
    private val expected = """{"codeloupe.repo":"Terrio","codeloupe.workspace":"TER-12","codeloupe.task":"TER-12"}"""

    private fun override(config: String) = ComposeOverride.build(Json.parseToJsonElement(config).jsonObject, owner)

    @Test
    fun `services, a built image, own volumes and networks get the labels`() {
        val result = override(
            """{"name":"p","services":{"web":{"image":"busybox"},"app":{"build":{"context":"."}}},
               "volumes":{"data":{"name":"p_data"}},"networks":{"default":{"name":"p_default"},"back":{"name":"p_back"}}}""",
        )
        val labels = Json.parseToJsonElement(expected)
        assertEquals(labels, result["services"]!!.jsonObject["web"]!!.jsonObject["labels"])
        assertEquals(labels, result["services"]!!.jsonObject["app"]!!.jsonObject["labels"])
        assertEquals(labels, result["services"]!!.jsonObject["app"]!!.jsonObject["build"]!!.jsonObject["labels"])
        assertFalse("build" in result["services"]!!.jsonObject["web"]!!.jsonObject)
        assertEquals(labels, result["volumes"]!!.jsonObject["data"]!!.jsonObject["labels"])
        assertEquals(setOf("default", "back"), result["networks"]!!.jsonObject.keys)
    }

    @Test
    fun `external volumes and networks are somebody else's and left out`() {
        val result = override(
            """{"services":{"web":{"image":"busybox"}},"volumes":{"mine":{},"shared":{"name":"shared","external":true}},
               "networks":{"outside":{"name":"outside","external":true},"old":{"external":{"name":"legacy"}}}}""",
        )
        assertEquals(setOf("mine"), result["volumes"]!!.jsonObject.keys)
        assertTrue("networks" !in result)
    }

    @Test
    fun `a project without services still produces a valid empty override`() {
        assertEquals(emptySet(), override("""{"name":"p"}""").keys)
    }
}
