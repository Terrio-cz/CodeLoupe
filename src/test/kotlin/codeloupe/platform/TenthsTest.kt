package codeloupe.platform

import codeloupe.metrics.MetricsRender
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class TenthsTest {
    @Test
    fun `ties are decided by the exact value of the double, as toFixed does`() {
        assertEquals(1.1, Tenths.of(1.15))
        assertEquals(0.1, Tenths.of(0.15))
        assertEquals(0.3, Tenths.of(0.25))
        assertEquals(2.5, Tenths.of(2.5))
        assertEquals(-0.3, Tenths.of(-0.25))
        assertEquals("1.1", Tenths.text(1.15))
        assertEquals("2.0", Tenths.text(2.0))
    }

    @Test
    fun `thousands in a report read as the script prints them, in any locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("cs-CZ"))
            assertEquals("1.1k", MetricsRender.fmt(1150))
            assertEquals("1.4k", MetricsRender.fmt(1450))
            assertEquals("1.5k", MetricsRender.fmt(1500))
            assertEquals("2.5M", MetricsRender.fmt(2_500_000))
            assertEquals("999", MetricsRender.fmt(999))
        } finally {
            Locale.setDefault(before)
        }
    }
}
