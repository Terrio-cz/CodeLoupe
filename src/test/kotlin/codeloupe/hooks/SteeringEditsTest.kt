package codeloupe.hooks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The decision table for edits and the PowerShell dialect: a search that feeds an edit, `grep -f`, `Get-Content` under its aliases. */
class SteeringEditsTest {
    private val big = "src/main/kotlin/com/acme/shop/OrderService.kt"
    private val files = mapOf(big to 400, "src/main/kotlin/com/acme/shop/Small.kt" to 30)

    private val posix = Steering(FakeSources("/repo", files), ShellPaths("/home/me", windows = false))
    private val windows = Steering(FakeSources("C:/work/repo", files), ShellPaths("C:/Users/me", windows = true))

    private fun bash(command: String) = posix.advise("Bash", JsonObject(mapOf("command" to JsonPrimitive(command))), "/repo", 150)?.call()

    private fun ps(command: String) = windows.advise("PowerShell", JsonObject(mapOf("command" to JsonPrimitive(command))), "C:/work/repo", 150)?.call()

    @Test
    fun `a search whose output feeds an edit is no search`() {
        listOf(
            "grep -rl Foo --include=*.kt src | xargs sed -i 's/Foo/Bar/g'",
            "rg -l Foo --glob '*.kt' src | xargs sed -i 's/Foo/Bar/g'",
            "rg -l Foo src | xargs -r sed -i.bak -e 's/Foo/Bar/'",
            "rg -l OrderService src | xargs -n1 -P4 sed -i 's/a/b/'",
            "rg -l OrderService src | xargs -I{} sed -i 's/a/b/' {}",
            "grep -rl OrderService src | xargs perl -pi -e 's/a/b/g'",
            "grep -rl OrderService src | xargs perl -i.orig -pe 's/a/b/g'",
            "git grep -l OrderService -- '*.kt' | xargs sed -i 's/a/b/'",
            "rg -l OrderService src | grep -v build | xargs sed -i 's/a/b/'",
            "rg -l OrderService src | xargs sd OrderService Orders",
            "rg -l OrderService src | xargs awk -i inplace '{ sub(/a/, \"b\") } 1'",
            "rg -l OrderService src | xargs sh -c 'sed -i s/a/b/ \"\$0\"'",
            "find src -name '*.kt' | xargs sed -i 's/a/b/'",
            "grep -rl OrderService src | sudo xargs sed -i 's/a/b/'",
        ).forEach { assertNull(bash(it), it) }
    }

    @Test
    fun `a search piped into something that only reads is still a search`() {
        assertEquals("""usages name="OrderService"""", bash("rg -l OrderService src | xargs wc -l"))
        assertEquals("""usages name="OrderService"""", bash("grep -rl OrderService src | xargs sed -n '1,5p'"))
        assertEquals("""usages name="OrderService"""", bash("rg -l OrderService src | sort | head -5"))
        assertEquals("""usages name="OrderService"""", bash("rg -l OrderService src | xargs grep -c Order"))
    }

    @Test
    fun `an edit that is not fed by the search does not hide it`() {
        assertEquals("""usages name="OrderService"""", bash("rg -l OrderService src && sed -i 's/a/b/' $big"))
        assertEquals("""usages name="OrderService"""", bash("rg -l OrderService src; xargs sed -i 's/a/b/' < list.txt"))
    }

    @Test
    fun `the value of a pattern file option is not a pattern and not a target`() {
        assertNull(bash("grep -f patterns.txt -r src"))
        assertNull(bash("grep -rf patterns.txt src"))
        assertNull(bash("grep --file=patterns.txt -r src"))
        assertNull(bash("grep --file patterns.txt -r src"))
        assertNull(bash("rg -f patterns.txt src"))
        assertNull(bash("rg --file patterns.txt src"))
        assertNull(bash("git grep -f patterns.txt -- '*.kt'"))
        // With a pattern of its own beside the file, that pattern still counts.
        assertEquals("""usages name="OrderService"""", bash("grep -f patterns.txt -e OrderService -r src"))
    }

    @Test
    fun `PowerShell aliases of Get-Content take its parameters`() {
        assertNull(ps("""cat src\main\kotlin\com\acme\shop\OrderService.kt -TotalCount 5"""))
        assertNull(ps("""type src\main\kotlin\com\acme\shop\OrderService.kt -Tail 20"""))
        assertNull(ps("""gc src\main\kotlin\com\acme\shop\OrderService.kt -Head 20"""))
        assertEquals("""outline target="$big"""", ps("""cat src\main\kotlin\com\acme\shop\OrderService.kt"""))
        assertEquals("""outline target="$big"""", ps("""type src\main\kotlin\com\acme\shop\OrderService.kt -Raw"""))
        assertEquals("""outline target="$big"""", ps("""gc -Path src\main\kotlin\com\acme\shop\OrderService.kt -Encoding utf8"""))
    }

    @Test
    fun `a PowerShell replace through a parenthesised read is an edit, not a read`() {
        assertNull(ps("""(Get-Content src\main\kotlin\com\acme\shop\OrderService.kt) -replace 'Foo','Bar' | Set-Content src\main\kotlin\com\acme\shop\OrderService.kt"""))
        assertNull(ps("""(cat src\main\kotlin\com\acme\shop\OrderService.kt) -replace 'Foo','Bar' | Out-File src\main\kotlin\com\acme\shop\OrderService.kt"""))
        assertNull(ps("""(Get-Content src\main\kotlin\com\acme\shop\OrderService.kt).Count"""))
        assertNull(ps("""(Get-Content src\main\kotlin\com\acme\shop\OrderService.kt -Raw) -replace 'Foo','Bar'"""))
    }

    @Test
    fun `a PowerShell search feeding Set-Content is no search`() {
        assertNull(ps("""Get-ChildItem -Recurse -Filter *.kt | Select-String Foo -List | ForEach-Object { (Get-Content ${'$'}_.Path) -replace 'Foo','Bar' | Set-Content ${'$'}_.Path }"""))
        assertEquals("""grep pattern="timeout" ignoreCase=true""", ps("""Get-ChildItem -Recurse -Filter *.kt | Select-String timeout"""))
    }

    @Test
    fun `a backtick in PowerShell escapes or continues the line, it is not a substitution`() {
        assertNull(ps("Get-Content `\n  src\\main\\kotlin\\com\\acme\\shop\\OrderService.kt `\n  -TotalCount 5"))
        assertEquals("""outline target="$big"""", ps("Get-Content `\n  src\\main\\kotlin\\com\\acme\\shop\\OrderService.kt"))
        assertEquals("""usages name="OrderService"""", ps("Select-String -Path src\\main\\kotlin\\**\\*.kt `\n -Pattern \"OrderService\""))
    }

    @Test
    fun `PowerShell control flow and the call operator keep their commands`() {
        assertEquals("""outline target="$big"""", ps("""& cat src\main\kotlin\com\acme\shop\OrderService.kt"""))
        assertEquals("""outline target="$big"""", ps("""if (Test-Path src) { Get-Content src\main\kotlin\com\acme\shop\OrderService.kt }"""))
        assertEquals("""outline target="$big"""", ps("""foreach (${'$'}f in 1..2) { Get-Content src\main\kotlin\com\acme\shop\OrderService.kt }"""))
    }

    @Test
    fun `Bash is parsed as before when the tool is Bash`() {
        assertEquals("""outline target="$big"""", bash("cat $big"))
        assertNull(bash("cat $big | head -50"))
    }
}
