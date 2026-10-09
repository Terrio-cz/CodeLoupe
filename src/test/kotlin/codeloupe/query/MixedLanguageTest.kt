package codeloupe.query

import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.JobQueue
import codeloupe.repo.Registry
import codeloupe.query.usages.CallsQuery
import codeloupe.query.usages.HierarchyQuery
import codeloupe.query.usages.HitLines
import codeloupe.query.usages.Label
import codeloupe.query.usages.UsageFinder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A repository of Kotlin and Java files, built by the build worker as a daemon would: every query sees both languages. */
class MixedLanguageTest {
    private val repo = TestRepos.fixtureRepo("mixed")

    private fun query(block: (View) -> Unit) = runBlocking {
        val config = Config(TestRepos.tmpDir("home"), 0, 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null)
        Registry(config, JobQueue(this)).query(repo.toString(), read = block)
    }

    private fun labels(view: View, query: String): Map<String, Label> =
        HitLines.lines(UsageFinder(view).usages(Resolver.resolve(view, query)))
            .associate { "${it.ref.path.substringAfterLast('/')}:${it.ref.line}" to it.label }

    @Test
    fun `find, outline and symbol work across both languages`() = query { view ->
        val found = FindQuery.run(view, FindQuery.Args("greet"))
        assertContains(found, "Greeter.kt:4")
        assertContains(found, "Shouter.java:")
        assertContains(FindQuery.run(view, FindQuery.Args("Shouter", kind = "class")), "public class Shouter extends Greeter")
        assertContains(OutlineQuery.run(view, "Shouter.java"), "public String greet(String name)")
        assertContains(OutlineQuery.run(view, "Greeter.kt"), "open fun greet(name: String): String")
        assertContains(SymbolQuery.run(view, SymbolQuery.Args("Shouter.loud")), "return greeter.greet(\"java\");")
        assertContains(SymbolQuery.run(view, SymbolQuery.Args("Greeter.standard")), "fun standard(): Greeter = Greeter()")
        assertContains(FindQuery.run(view, FindQuery.Args("Greeter", kind = "class")), "open class Greeter")
    }

    @Test
    fun `usages follow a name from one language into the other`() = query { view ->
        val greet = labels(view, "Greeter.greet")
        assertEquals(Label.EXACT, greet["Greeter.kt:11"], "Kotlin calls the Kotlin member")
        assertEquals(Label.EXACT, greet["Shouter.java:12"], "a Java super call of a Kotlin member")
        assertEquals(Label.EXACT, greet["Shouter.java:16"], "Java calls a Kotlin member through a parameter")
        assertEquals(Label.EXACT, greet["Shouter.java:20"], "a lambda parameter typed by the stream")
        assertEquals(Label.CANDIDATE, greet["Greeter.kt:13"], "Kotlin calls the Java override")
        assertEquals(Label.CANDIDATE, greet["Report.java:11"], "Java calls the Java override")
        val shouter = labels(view, "Shouter.greet")
        assertEquals(Label.EXACT, shouter["Greeter.kt:13"])
        assertEquals(Label.EXACT, shouter["Report.java:11"])
        assertEquals(Label.EXACT, labels(view, "Shouter")["Report.java:11"], "a Java class named from Java")
        assertEquals(Label.EXACT, labels(view, "com.example.mixed.Greeter")["Shouter.java:5"], "a Java supertype that is Kotlin")
        assertEquals(Label.EXACT, labels(view, "Mixer")["Report.java:4"], "a Kotlin class named from Java")
    }

    @Test
    fun `hierarchy and calls cross the language line`() = query { view ->
        val hierarchy = HierarchyQuery.run(view, "Greeter", supers = true)
        assertContains(hierarchy, "Shouter.java:5  public class Shouter extends Greeter")
        assertContains(HierarchyQuery.run(view, "Shouter", supers = true), "Greeter.kt:3  open class Greeter")
        assertContains(HierarchyQuery.run(view, "Greeter.greet"), "Shouter.java:10  [Shouter] public String greet(…)")
        val callers = CallsQuery.run(view, CallsQuery.Args("Greeter.greet", depth = 1))
        assertContains(callers, "Greeter.kt")
        assertContains(callers, "Shouter.java")
    }

    @Test
    fun `the status counts the files of both languages`() = query { view ->
        assertTrue(view.filesBySuffix(".java").size == 2 && view.filesBySuffix(".kt").size == 1)
    }
}
