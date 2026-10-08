package codeloupe.query.usages

import codeloupe.TestRepos
import codeloupe.golden.IdentifierScan
import codeloupe.golden.JavaIdentifierScan
import codeloupe.index.BaseBuilder
import codeloupe.query.Resolver
import codeloupe.query.View
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kotlin usage tests on a Java fixture: the same questions, with the constructs Java has for them. */
class JavaUsagesTest {
    private val view = View(DB)

    @AfterTest
    fun close() = view.close()

    /** `File.java:line` -> label of every line holding a reference with the target's name. */
    private fun labels(query: String): Map<String, Label> {
        val targets = Resolver.resolve(view, query)
        assertTrue(targets.isNotEmpty(), "\"$query\" resolves")
        return HitLines.lines(UsageFinder(view).usages(targets)).associate { "${it.ref.path.substringAfterLast('/')}:${it.ref.line}" to it.label }
    }

    @Test
    fun `receivers - super, collection elements, casts, typed parameters, overrides`() {
        assertEquals(
            mapOf(
                "SavingsAccount.java:10" to Label.EXACT, "AccountService.java:35" to Label.EXACT, "AccountService.java:49" to Label.OTHER,
                "AccountService.java:53" to Label.EXACT, "Report.java:18" to Label.EXACT, "Report.java:19" to Label.CANDIDATE, "Report.java:20" to Label.OTHER,
            ),
            labels("Account.describe"),
        )
    }

    @Test
    fun `overloads by argument count, constructor receivers`() {
        assertEquals(
            mapOf(
                "Account.java:27" to Label.EXACT, "AccountService.java:29" to Label.EXACT, "AccountService.java:57" to Label.OTHER,
                "Report.java:21" to Label.EXACT, "Report.java:22" to Label.OTHER,
            ),
            labels("Account.rename(_)"),
        )
    }

    @Test
    fun `types through static imports, enums and overridden members`() {
        val account = labels("com.example.model.Account")
        assertEquals(Label.EXACT, account["SavingsAccount.java:3"], "supertype")
        assertEquals(Label.EXACT, account["AccountService.java:19"], "declared type")
        assertEquals(mapOf("AccountService.java:19" to Label.EXACT), labels("Account.create"), "a static import")
        assertEquals(Label.EXACT, labels("Level.atLeast")["AccountService.java:45"])
        assertEquals(mapOf("AccountService.java:41" to Label.EXACT, "Edge.java:46" to Label.OTHER), labels("Accounts.label"))
        assertEquals(mapOf("AccountService.java:20" to Label.CANDIDATE), labels("Store.save"), "the call reaches the override")
        assertEquals(mapOf("AccountService.java:20" to Label.EXACT), labels("AccountStore.save"))
    }

    @Test
    fun `a private declaration is out of reach from other files`() {
        assertEquals(mapOf("Account.java:39" to Label.EXACT, "Report.java:23" to Label.OTHER), labels("Account.hidden"))
    }

    @Test
    fun `method references, pattern variables, static imports`() {
        assertEquals(mapOf("Edge.java:10" to Label.EXACT, "Edge.java:19" to Label.EXACT), labels("Point.area"))
        assertEquals(mapOf("Edge.java:14" to Label.EXACT), labels("Circle.radius"), "reached through a pattern variable")
        assertEquals(mapOf("Edge.java:23" to Label.EXACT, "Edge.java:75" to Label.OTHER), labels("Accounts.helper"), "a static import; a parameter of that name is no method")
        assertEquals(mapOf("Edge.java:27" to Label.EXACT), labels("Accounts.shared"))
    }

    @Test
    fun `scopes - shadowing members of an anonymous class, nested types, qualified types, constructors`() {
        assertEquals(mapOf("Edge.java:23" to Label.OTHER, "Edge.java:75" to Label.OTHER), labels("Outer.helper"), "inside an anonymous class its own member wins")
        assertEquals(mapOf("Edge.java:76" to Label.OTHER), labels("Outer.label2"))
        assertEquals(mapOf("Sub.java:5" to Label.EXACT), labels("Nested.make"))
        assertEquals(Label.EXACT, labels("com.example.model.Point")["Edge.java:34"], "a package-qualified type")
        val constructor = labels("Point(int)")
        assertEquals(Label.CANDIDATE, constructor["Edge.java:31"], "a constructor call that fits")
        assertEquals(Label.OTHER, constructor["Edge.java:18"], "one with two arguments does not")
    }

    @Test
    fun `lambda parameters - typed by the call they are passed to and by the declared functional interface`() {
        assertEquals(mapOf("Dsl.java:34" to Label.EXACT), labels("Builder.add2"))
        assertEquals(mapOf("Dsl.java:34" to Label.OTHER), labels("Use.add2"))
        assertEquals(mapOf("Dsl.java:38" to Label.EXACT, "Dsl.java:40" to Label.OTHER), labels("Bag.clear3"))
        assertEquals(mapOf("Dsl.java:38" to Label.OTHER, "Dsl.java:40" to Label.EXACT), labels("Sack.clear3"))
    }

    @Test
    fun `ambiguous names must be qualified`() {
        assertContains(UsagesQuery.run(view, UsagesQuery.Args("label")), "declarations match \"label\" — qualify it")
    }

    @Test
    fun `every rg -w code position is in the result`() {
        val queries = listOf(
            "Account.describe", "Account.rename", "Account", "Store.save", "Level.atLeast", "Accounts.label", "Point.area", "Circle.radius",
            "Accounts.helper", "Nested.make", "Named.label", "Accounts.shared", "Account.hidden", "Outer.helper", "Builder.add2",
        )
        val targets = queries.associateWith { Resolver.resolve(view, it) }
        val baseline = JavaIdentifierScan.positions(view, targets.values.flatten().map { it.name }.toSet())
        for ((query, decls) in targets) {
            val found = UsageFinder(view).usages(decls).map { IdentifierScan.Position(it.ref.path, it.ref.line, it.ref.col) }.toSet()
            val missing = decls.map { it.name }.toSet().flatMap { baseline.getValue(it) }.filter { it !in found }
            assertEquals(emptyList(), missing, query)
        }
    }

    @Test
    fun `usages output - grouped by file and declaration, one line per hit, others counted`() {
        val text = UsagesQuery.run(view, UsagesQuery.Args("Account.describe"))
        assertTrue(text.startsWith("usages of src/main/java/com/example/model/Account.java:18-20  [Account] public String describe()\n4 exact, 1 candidate\n"), text)
        assertContains(text, "\nsrc/main/java/com/example/other/Report.java\n  [Report] public void run(…)\n  18 = account.describe();\n  19 ? savings.describe();")
        assertTrue(text.endsWith("2 more lines with the name resolve to other declarations (all=true lists them)"), text)
    }

    @Test
    fun `calls - callers and callees trees`() {
        val callers = CallsQuery.run(view, CallsQuery.Args("Account.rename(_)", depth = 2))
        assertContains(callers, "\n  = src/main/java/com/example/service/AccountService.java:24  [AccountService] public Account rename(…)  @29")
        assertContains(callers, "\n    = src/main/java/com/example/other/Report.java:17  [Report] public void run(…)  @22")
        val callees = CallsQuery.run(view, CallsQuery.Args("AccountService.rename", callees = true))
        assertContains(callees, "= src/main/java/com/example/service/AccountStore.java:16  [AccountStore] public Account find(…)  @25")
        assertContains(callees, "= src/main/java/com/example/model/Account.java:22  [Account] public Account rename(…)  @29")
    }

    @Test
    fun `context - source, direct callers and callees in one answer`() {
        val out = ContextQuery.run(view, ContextQuery.Args("AccountService.rename"))
        assertContains(out, "public Account rename(String id, String to) {")
        assertContains(out, "callers of ")
        assertContains(out, "= src/main/java/com/example/other/Report.java:17  [Report] public void run(…)  @22")
        assertContains(out, "callees of ")
        assertContains(out, "= src/main/java/com/example/model/Account.java:22  [Account] public Account rename(…)  @29")
    }

    @Test
    fun `hierarchy - subtypes, supertypes and overrides`() {
        assertContains(HierarchyQuery.run(view, "com.example.model.Account"), "subtypes:\n  ./SavingsAccount.java:3  public class SavingsAccount extends Account")
        assertContains(HierarchyQuery.run(view, "AccountStore"), "supertypes:\n  src/main/java/com/example/model/Store.java:3  public interface Store<T>")
        assertContains(HierarchyQuery.run(view, "Account.describe"), "overridden by:\n  ./SavingsAccount.java:8  [SavingsAccount] public String describe()")
        assertContains(HierarchyQuery.run(view, "AccountStore.find"), "overrides:\n  src/main/java/com/example/model/Store.java:6  [Store] T find(…)")
        assertContains(HierarchyQuery.run(view, "Shape"), "subtypes:\n  ./Circle.java:3  public class Circle extends Shape")
        assertContains(HierarchyQuery.run(view, "Named"), "subtypes:\n  src/main/java/com/example/other/Edge.java:39  [Edge.lit] new Named()", message = "an anonymous class")
        assertContains(HierarchyQuery.run(view, "Level"), "subtypes:\n  ./Level.java:4  [Level] LOW", message = "enum constants")
        assertContains(HierarchyQuery.run(view, "Named.label"), "overridden by:\n  src/main/java/com/example/other/Edge.java:40  [Edge.lit.<anonymous>] public String label()")
    }

    private companion object {
        val REPO = TestRepos.fixtureRepo("java/usages")
        val DB = TestRepos.tmpDir("java-usages").resolve("base.db").also { BaseBuilder.build(REPO.toString(), TestRepos.git(REPO, "rev-parse", "HEAD"), it) }
    }
}
