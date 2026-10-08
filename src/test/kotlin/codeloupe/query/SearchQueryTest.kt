package codeloupe.query

import codeloupe.TestRepos
import codeloupe.index.BaseBuilder
import codeloupe.index.IndexedFile
import codeloupe.index.Store
import codeloupe.index.StoreWriter
import codeloupe.lang.Languages
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `find mode=search`: words in, ranked declarations out, from the base and from a worktree overlay. */
class SearchQueryTest {
    private val billing = """
        package com.example.billing

        /** Computes the monthly token limit of an organization from its plan. */
        fun computeTokenLimit(plan: String): Long = 0

        /** Schedules a retry after a failed delivery with exponential backoff. */
        class RetryScheduler {
            fun scheduleRetry(attempt: Int): Long = attempt * 2L
        }

        class Unrelated {
            fun noise() = Unit
        }
    """.trimIndent() + "\n"

    private val checksum = """
        package com.example.integrity;

        /** Verifies the checksum of a downloaded archive before it is unpacked. */
        public class ChecksumVerifier {
            public boolean verify(byte[] archive, String expected) { return true; }
        }
    """.trimIndent() + "\n"

    private val limitsPath = "billing/src/main/kotlin/com/example/billing/Limits.kt"

    private val repo = TestRepos.fixtureRepo(
        "kotlin/sample",
        mapOf(
            limitsPath to billing,
            "integrity/src/main/java/com/example/integrity/ChecksumVerifier.java" to checksum,
            "billing/src/test/kotlin/com/example/billing/LimitsTest.kt" to "package com.example.billing\n\nclass LimitsTest {\n    fun tokenLimitIsComputed() = Unit\n}\n",
        ),
    )
    private val dir = TestRepos.tmpDir("search")
    private val base: Path = dir.resolve("base.db").also { BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), it) }

    private fun search(
        q: String, kind: String? = null, module: String? = null, test: Boolean? = null, limit: Int? = null, overlay: Path? = null,
    ): String = View(base, overlay).use { FindQuery.run(it, FindQuery.Args(q, kind = kind, module = module, test = test, limit = limit, mode = "search")) }

    private fun find(q: String): String = View(base).use { FindQuery.run(it, FindQuery.Args(q)) }

    @Test
    fun `words of a name, of its KDoc and of its container find a Kotlin declaration`() {
        assertContains(search("token limit").lines().first(), "computeTokenLimit")
        assertContains(search("exponential backoff for a failed delivery").lines().first(), "RetryScheduler")
        assertContains(search("schedule retry").lines().take(3).joinToString(" | "), "scheduleRetry")
        assertContains(search("monthly limit of an organization", module = "billing"), "Limits.kt")
    }

    @Test
    fun `Java declarations are found by their Javadoc`() {
        val first = search("verify checksum of a downloaded archive").lines().first()
        assertContains(first, "ChecksumVerifier")
        assertContains(first, "(verify, checksum, downloaded, archive)")
    }

    @Test
    fun `plural and tense of a word do not matter`() {
        assertContains(search("tokens limited").lines().first(), "computeTokenLimit")
    }

    @Test
    fun `filters narrow the hits and tests rank below sources`() {
        assertFalse("Limits.kt" in search("token limit", module = "integrity"))
        assertContains(search("token limit", test = true), "LimitsTest.kt")
        assertFalse("LimitsTest.kt" in search("token limit", test = false))
        assertContains(search("token limit").lines().first(), "Limits.kt:")
        assertEquals(1, search("token limit", limit = 1).lines().size)
        assertContains(search("retry", kind = "class"), "class RetryScheduler")
        assertFalse("scheduleRetry" in search("retry", kind = "class"))
    }

    @Test
    fun `words with spaces mean search, a single word keeps meaning a name`() {
        assertEquals(search("token limit"), find("token limit"))
        assertEquals("$limitsPath:6-9  class RetryScheduler", find("RetryScheduler"))
        assertContains(find("Nothing.at(all, here)"), "no declaration matches")
    }

    @Test
    fun `no match, only function words`() {
        assertEquals("no declaration matches the words of \"zebra stripes\"", search("zebra stripes"))
        assertEquals("no searchable words in \"where is the\"", search("where is the"))
    }

    @Test
    fun `an overlay edit shows in the next search and hides the base copy of the file`() {
        val overlay = dir.resolve("overlay.db")

        fun put(text: String, hash: String) = Store.open(overlay).use { db ->
            StoreWriter(db).use { it.put(IndexedFile(limitsPath, "kotlin", hash, text.length.toLong(), content = text), Languages.extract(limitsPath, text)!!) }
        }
        put("package com.example.billing\n\n/** Throttles requests per second for one API key. */\nfun throttleRequests(key: String): Boolean = true\n", "x")
        assertContains(search("throttle requests per second", overlay = overlay), "throttleRequests")
        assertFalse("computeTokenLimit" in search("token limit", overlay = overlay), "the base copy of an edited file is hidden")
        assertContains(search("checksum archive", overlay = overlay), "ChecksumVerifier")

        // The next refresh replaces the file again: the rows of the earlier edit must not linger.
        put("package com.example.billing\n\nfun somethingElse() = Unit\n", "y")
        assertFalse("throttleRequests" in search("throttle requests per second", overlay = overlay))
        assertContains(search("something else", overlay = overlay), "somethingElse")

        Store.open(overlay).use { db -> StoreWriter(db).use { it.tombstone(limitsPath) } }
        assertFalse("somethingElse" in search("something else", overlay = overlay))
        assertTrue(search("token limit", overlay = overlay).startsWith("no declaration matches") || "computeTokenLimit" !in search("token limit", overlay = overlay))
    }
}
