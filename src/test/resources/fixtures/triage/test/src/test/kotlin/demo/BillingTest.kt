package demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BillingTest {
    private val billing = Billing()

    @Test
    fun `a small amount is not discounted`() {
        assertEquals(50, billing.total(50))
    }

    @Test
    fun `a large amount gets ten off`() {
        assertEquals(190, billing.total(200))
    }

    @Test
    fun `tax is a fifth`() {
        assertEquals(40, billing.tax(100))
        assertTrue(billing.label(5).startsWith("total"))
    }

    @Test
    fun `report shows the rate`() {
        assertEquals("rate 10 total 180", Report(billing).render(200))
    }
}
