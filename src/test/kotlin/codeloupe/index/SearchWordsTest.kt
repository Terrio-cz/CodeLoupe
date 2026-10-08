package codeloupe.index

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchWordsTest {
    @Test
    fun `identifiers are cut at humps, underscores, digits and dots`() {
        assertEquals(listOf("comput", "token", "limit"), SearchWords.split("computeTokenLimit"))
        assertEquals(listOf("http", "server", "config"), SearchWords.split("HTTPServerConfig"))
        assertEquals(listOf("max", "retry", "count"), SearchWords.split("MAX_RETRY_COUNT"))
        assertEquals(listOf("sha", "256"), SearchWords.split("sha256"))
        assertEquals(listOf("com", "exampl", "shop"), SearchWords.split("com.example.shop"))
    }

    @Test
    fun `plural and verb endings fold to one stem`() {
        for (group in listOf(
            listOf("limit", "limits", "limited", "limiting"),
            listOf("compute", "computes", "computed", "computing"),
            listOf("cache", "caches", "cached"),
            listOf("entry", "entries"),
            listOf("class", "classes"),
        )) {
            assertEquals(1, group.map { SearchWords.split(it).single() }.toSet().size, "$group")
        }
    }

    @Test
    fun `a query drops function words and repeats`() {
        val terms = SearchWords.terms("Where is the token limit of the tokens computed?")
        assertEquals(listOf("token", "limit", "comput"), terms.keys.toList())
        assertEquals("token", terms.getValue("token"))
    }

    @Test
    fun `signatures lose their keywords`() {
        assertEquals(listOf("retry", "attempt", "delay"), SearchWords.signature("private suspend fun retry(attempt: Int, delay: Long): Unit"))
    }
}
