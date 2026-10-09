package codeloupe.reconcile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconcileRoutesTest {
    private fun auto(body: String) = wantsAuto(Json.parseToJsonElement(body).jsonObject)

    @Test
    fun `a call runs the auto entries unless it says it does not`() {
        assertEquals(true, auto("{}"))
        assertEquals(true, auto("""{"confirm":["volume:x"]}"""))
        assertEquals(false, auto("""{"confirm":["volume:x"],"auto":false}"""))
        assertEquals(true, auto("""{"auto":"no"}"""), "only a real false switches it off")
    }
}
