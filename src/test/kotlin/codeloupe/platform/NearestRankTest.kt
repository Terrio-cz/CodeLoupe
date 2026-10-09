package codeloupe.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class NearestRankTest {
    @Test
    fun `the median of an odd and an even count is the nearest rank, not the one below`() {
        assertEquals(20, NearestRank.of(listOf(30, 10, 20), 0.5))
        assertEquals(20, NearestRank.of(listOf(10, 20, 30, 40), 0.5))
    }

    @Test
    fun `the 95th percentile of ten values is the tenth`() {
        assertEquals(100, NearestRank.of((1L..10L).map { it * 10 }, 0.95))
        assertEquals(190, NearestRank.of((1L..20L).map { it * 10 }, 0.95))
    }

    @Test
    fun `no values and one value`() {
        assertEquals(0, NearestRank.of(emptyList(), 0.95))
        assertEquals(7, NearestRank.of(listOf(7), 0.5))
        assertEquals(7, NearestRank.of(listOf(7), 0.0))
    }
}
