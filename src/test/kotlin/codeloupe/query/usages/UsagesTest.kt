package codeloupe.query.usages

import codeloupe.TestRepos
import codeloupe.golden.IdentifierScan
import codeloupe.index.BaseBuilder
import codeloupe.query.Resolver
import codeloupe.query.View
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsagesTest {
    private val view = View(DB)

    @AfterTest
    fun close() = view.close()

    /** `File.kt:line` -> label of every line holding a reference with the target's name. */
    private fun labels(query: String): Map<String, Label> {
        val targets = Resolver.resolve(view, query)
        assertTrue(targets.isNotEmpty(), "\"$query\" resolves")
        return HitLines.lines(UsageFinder(view).usages(targets)).associate { "${it.ref.path.substringAfterLast('/')}:${it.ref.line}" to it.label }
    }

    @Test
    fun `receivers - super, collection elements, casts, typed parameters, overrides`() {
        assertEquals(
            mapOf(
                "Account.kt:21" to Label.EXACT, "AccountService.kt:33" to Label.EXACT, "AccountService.kt:41" to Label.OTHER,
                "AccountService.kt:43" to Label.EXACT, "Report.kt:10" to Label.EXACT, "Report.kt:11" to Label.CANDIDATE, "Report.kt:12" to Label.OTHER,
            ),
            labels("Account.describe"),
        )
    }

    @Test
    fun `overloads by argument count, lambda receivers, import aliases, constructor receivers`() {
        assertEquals(
            mapOf(
                "Account.kt:13" to Label.EXACT, "AccountService.kt:30" to Label.EXACT, "AccountService.kt:39" to Label.EXACT,
                "AccountService.kt:45" to Label.OTHER, "Report.kt:13" to Label.EXACT, "Report.kt:14" to Label.EXACT, "Report.kt:15" to Label.OTHER,
            ),
            labels("Account.rename(_)"),
        )
    }

    @Test
    fun `types through aliases, companions, enums, extensions and overridden members`() {
        val account = labels("com.example.model.Account")
        assertEquals(Label.EXACT, account["AccountService.kt:39"], "Acc is an import alias of Account")
        assertEquals(Label.EXACT, account["Account.kt:20"], "supertype")
        assertEquals(mapOf("AccountService.kt:23" to Label.EXACT), labels("Account.create"))
        assertEquals(Label.EXACT, labels("Level.atLeast")["AccountService.kt:37"])
        assertEquals(mapOf("AccountService.kt:35" to Label.EXACT, "Edge.kt:25" to Label.OTHER), labels("Account.label"))
        assertEquals(mapOf("AccountService.kt:24" to Label.CANDIDATE), labels("Store.save"), "the call reaches the override")
        assertEquals(mapOf("AccountService.kt:24" to Label.EXACT), labels("AccountStore.save"))
    }

    @Test
    fun `a private declaration is out of reach from other files`() {
        assertEquals(mapOf("Account.kt:37" to Label.EXACT, "Report.kt:16" to Label.OTHER), labels("Account.kt:35"))
    }

    @Test
    fun `callable references, smart casts, this in apply, DSL receivers, library supertypes`() {
        assertEquals(mapOf("Edge.kt:6" to Label.EXACT, "Edge.kt:10" to Label.EXACT, "Edge.kt:28" to Label.EXACT), labels("Point.area"))
        assertEquals(mapOf("Edge.kt:8" to Label.CANDIDATE), labels("Circle.radius"), "reached through a smart cast")
        assertEquals(mapOf("Edge.kt:12" to Label.CANDIDATE), labels("report"), "IllegalStateException is a Throwable the index cannot see")
        assertEquals(Label.CANDIDATE, labels("Named.label")["Edge.kt:25"], "a local across an object body may be the object's member")
    }

    @Test
    fun `scopes - shadowing bindings, private declarations elsewhere, nested types of supertypes, qualified types`() {
        assertEquals(mapOf("Edge.kt:14" to Label.EXACT), labels("com.example.model.helper"), "a Boolean binding cannot be called")
        assertEquals(mapOf("Edge.kt:16" to Label.EXACT), labels("com.example.model.shared"), "a private shared() in another file hides nothing")
        assertEquals(mapOf("Shapes.kt:12" to Label.EXACT), labels("Nested.make"))
        assertEquals(Label.EXACT, labels("com.example.model.Point")["Edge.kt:20"], "a package-qualified type")
        assertEquals(Label.CANDIDATE, labels("Point(Int)")["Edge.kt:18"], "a secondary constructor call")
    }

    @Test
    fun `ambiguous names must be qualified`() {
        assertContains(UsagesQuery.run(view, UsagesQuery.Args("label")), "declarations match \"label\" — qualify it")
    }

    @Test
    fun `every rg -w code position is in the result`() {
        val queries = listOf(
            "Account.describe", "Account.rename", "Account", "Store.save", "Level.atLeast", "Account.label", "Point.area", "Circle.radius",
            "report", "Nested.make", "Named.label", "com.example.model.shared", "com.example.model.helper",
        )
        val targets = queries.associateWith { Resolver.resolve(view, it) }
        val baseline = IdentifierScan.positions(view, targets.values.flatten().map { it.name }.toSet())
        for ((query, decls) in targets) {
            val found = UsageFinder(view).usages(decls).map { IdentifierScan.Position(it.ref.path, it.ref.line, it.ref.col) }.toSet()
            val missing = decls.map { it.name }.toSet().flatMap { baseline.getValue(it) }.filter { it !in found }
            assertEquals(emptyList(), missing, query)
        }
    }

    @Test
    fun `usages output - grouped by file and declaration, one line per hit, others counted`() {
        val text = UsagesQuery.run(view, UsagesQuery.Args("Account.describe"))
        assertTrue(text.startsWith("usages of src/main/kotlin/com/example/model/Account.kt:9-9  [Account] open fun describe(): String\n4 exact, 1 candidate\n"), text)
        assertContains(text, "\nsrc/main/kotlin/com/example/other/Report.kt\n  [Report] fun run(…)\n  10 = account.describe()\n  11 ? savings.describe()")
        assertTrue(text.endsWith("2 more lines with the name resolve to other declarations (all=true lists them)"))
        val limited = UsagesQuery.run(view, UsagesQuery.Args("Account.describe", limit = 2, all = true))
        assertContains(limited, "… +5 more in 2 files (raise limit)")
        assertContains(UsagesQuery.run(view, UsagesQuery.Args("Nope")), "no declaration \"Nope\"")
    }

    @Test
    fun `calls - callers and callees trees`() {
        val callers = CallsQuery.run(view, CallsQuery.Args("Account.rename(_)", depth = 2))
        assertContains(callers, "\n  = src/main/kotlin/com/example/service/AccountService.kt:28  [AccountService] fun rename(…)  @30")
        assertContains(callers, "\n    = src/main/kotlin/com/example/other/Report.kt:9  [Report] fun run(…)  @15")
        val callees = CallsQuery.run(view, CallsQuery.Args("AccountService.rename", callees = true))
        assertContains(callees, "= src/main/kotlin/com/example/service/AccountService.kt:16  [AccountStore] override fun find(…)  @29")
        assertContains(callees, "= src/main/kotlin/com/example/model/Account.kt:11  [Account] fun rename(…)  @30")
    }

    @Test
    fun `hierarchy - subtypes, supertypes and overrides`() {
        assertContains(HierarchyQuery.run(view, "com.example.model.Account"), "subtypes:\n  src/main/kotlin/com/example/model/Account.kt:20  class SavingsAccount(…)")
        assertContains(HierarchyQuery.run(view, "AccountStore"), "supertypes:\n  src/main/kotlin/com/example/model/Account.kt:3  interface Store<T>")
        assertContains(HierarchyQuery.run(view, "Account.describe"), "overridden by:\n  src/main/kotlin/com/example/model/Account.kt:21  [SavingsAccount] override fun describe()")
        assertContains(HierarchyQuery.run(view, "AccountStore.find"), "overrides:\n  src/main/kotlin/com/example/model/Account.kt:5  [Store] fun find(…)")
        assertContains(HierarchyQuery.run(view, "Shape"), "subtypes:\n  src/main/kotlin/com/example/model/Shapes.kt:23  class Circle : Shape()")
    }

    private companion object {
        val REPO = TestRepos.fixtureRepo("kotlin/usages")
        val DB = TestRepos.tmpDir("usages").resolve("base.db").also { BaseBuilder.build(REPO.toString(), TestRepos.git(REPO, "rev-parse", "HEAD"), it) }
    }
}
