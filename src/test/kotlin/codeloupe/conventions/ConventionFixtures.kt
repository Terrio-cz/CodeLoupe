package codeloupe.conventions

import codeloupe.TestRepos
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** A small Gradle-like repository on disk with one module `:core` that follows a few conventions on purpose. */
object ConventionFixtures {
    private const val MAIN = "core/src/main/kotlin/com/example/core"
    private const val TEST = "core/src/test/kotlin/com/example/core"

    fun repo(): Path {
        val root = TestRepos.tmpDir("conventions")
        val files = mapOf(
            "$MAIN/store/UserStore.kt" to store("UserStore"),
            "$MAIN/store/OrderStore.kt" to store("OrderStore"),
            "$MAIN/store/ItemStore.kt" to store("ItemStore"),
            "$MAIN/http/UserRoutes.kt" to "package com.example.core.http\n\nimport io.ktor.server.routing.Route\nimport io.ktor.server.routing.get\nimport com.example.core.store.UserStore\n\n/** Routes of users. */\ninternal object UserRoutes {\n    fun install(route: Route, store: UserStore) {\n        require(store.size() >= 0) { \"never negative\" }\n        // throw IllegalStateException(\"only a comment\")\n        val note = \"throw IllegalStateException(marker-literal-7)\"\n        println(note)\n    }\n}\n",
            "$MAIN/http/Helpers.kt" to "package com.example.core.http\n\nimport io.ktor.server.routing.Route\n\nfun Route.ping() = Unit\n\nval LIMIT = 10\n",
            "$MAIN/model/Kinds.kt" to "package com.example.core.model\n\nenum class Kind { A, B }\n\ndata class Money(val cents: Long)\n",
            "$MAIN/model/Result.kt" to "package com.example.core.model\n\nsealed interface Result\n",
            "$TEST/store/UserStoreTest.kt" to test("UserStoreTest", "UserStore"),
            "$TEST/store/OrderStoreTest.kt" to test("OrderStoreTest", "OrderStore"),
            "$TEST/store/ItemStoreTest.kt" to test("ItemStoreTest", "ItemStore"),
            "$TEST/store/StoreFixtures.kt" to "package com.example.core.store\n\nclass StoreFixtures {\n    fun user() = 1\n}\n",
            "core/build.gradle.kts" to "plugins { kotlin(\"jvm\") }\n",
            "core/build/generated/Generated.kt" to "package generated\n\nclass Generated\n",
            "core/src/main/resources/application.conf" to "value = \"marker-resource-9\"\n",
            "tools/cli/src/main/kotlin/Main.kt" to "package tools.cli\n\nfun main() = Unit\n",
        )
        for ((path, text) in files) root.resolve(path).also { it.parent.createDirectories() }.writeText(text)
        return root
    }

    private fun store(name: String) = """
        package com.example.core.store

        import kotlinx.serialization.Serializable
        import org.slf4j.LoggerFactory

        /** The $name. */
        class $name : AutoCloseable {
            private val log = LoggerFactory.getLogger($name::class.java)

            fun size(): Int {
                check(open) { "closed" }
                try {
                    return 0
                } catch (e: IllegalStateException) {
                    throw StoreException("failed")
                }
            }

            override fun close() = Unit
        }
    """.trimIndent() + "\n"

    private fun test(name: String, subject: String) = """
        package com.example.core.store

        import kotlin.test.Test
        import kotlin.test.assertEquals

        class $name {
            private val fixtures = StoreFixtures()

            @Test
            fun `${subject.lowercase()} starts empty`() {
                assertEquals(0, $subject().size())
            }

            @Test
            fun plainName() = Unit
        }
    """.trimIndent() + "\n"
}
