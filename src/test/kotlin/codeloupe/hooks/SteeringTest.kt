package codeloupe.hooks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The decision table: what a hook says to real command shapes, and what it must leave alone. */
class SteeringTest {
    private val big = "src/main/kotlin/com/acme/shop/OrderService.kt"
    private val small = "src/main/kotlin/com/acme/shop/Small.kt"
    private val files = mapOf(big to 400, small to 30, "src/test/kotlin/com/acme/shop/OrderServiceTest.kt" to 220, "app/Thing.java" to 500)

    private val posix = Steering(FakeSources("/repo", files), ShellPaths("/home/me", windows = false))
    private val windows = Steering(FakeSources("C:/work/repo", files), ShellPaths("C:/Users/me", windows = true))

    private fun bash(steering: Steering, command: String, cwd: String, tool: String = "Bash"): Advice? =
        steering.advise(tool, JsonObject(mapOf("command" to JsonPrimitive(command))), cwd, minLines = 150)

    private fun shell(command: String) = bash(posix, command, "/repo")?.call()

    private fun read(path: String, steering: Steering = posix, cwd: String = "/repo", extra: Map<String, Int> = emptyMap()) =
        steering.advise("Read", JsonObject(mapOf("file_path" to JsonPrimitive(path)) + extra.mapValues { JsonPrimitive(it.value) }), cwd, 150)?.call()

    @Test
    fun `searches for a declaration are answered by find`() {
        assertEquals("""find q="OrderService"""", shell("""rg "class OrderService" src/main"""))
        assertEquals("""find q="parseConfig" kind="fun"""", shell("""rg -n "fun parseConfig" --glob '*.kt'"""))
        assertEquals("""find q="Repo" kind="interface"""", shell("""grep -rn "interface Repo" src --include=*.kt"""))
        assertEquals("""find q="Settings" kind="object"""", shell("""rg '^\s*object Settings' src"""))
    }

    @Test
    fun `searches for a name are answered by usages`() {
        assertEquals("""usages name="OrderService"""", shell("rg -w OrderService"))
        assertEquals("""usages name="OrderService"""", shell("""grep -rn "OrderService" src/"""))
        assertEquals("""usages name="OrderService"""", shell("""cd /repo && rg -n "\bOrderService\b" -g "*.kt" | head -20"""))
        assertEquals("""usages name="parseConfig"""", shell("""rg 'parseConfig\(' src/main"""))
        assertEquals("""usages name="MAX_SIZE"""", shell("""rg -F MAX_SIZE src"""))
        assertEquals("""usages name="OrderService"""", shell("rg -l OrderService --type kotlin"))
        assertEquals("""usages name="OrderService"""", shell("timeout 60 rg -n OrderService src"))
        assertEquals("""usages name="OrderService"""", shell("RUST_LOG=off rg OrderService src"))
        assertEquals("""usages name="OrderService"""", shell("""bash -c "rg OrderService src""""))
        assertEquals("""usages name="OrderService"""", shell("git grep -n OrderService -- '*.kt'"))
    }

    @Test
    fun `other searches are answered by grep`() {
        assertEquals("""grep pattern="timeout"""", shell("""grep -rn "timeout" src/ --include=*.kt"""))
        assertEquals("""grep pattern="SELECT .* FROM orders" regex=true""", shell("""rg "SELECT .* FROM orders" src/main -g '*.kt'"""))
        assertEquals("""grep pattern="order_id" ignoreCase=true""", shell("rg -i order_id src/main/kotlin"))
        assertEquals("""grep pattern="OrderService|Customer" regex=true""", shell("rg -e OrderService -e Customer src"))
        assertEquals("""grep pattern="fun \w+\(" regex=true""", shell("""rg 'fun \w+\(' src"""))
        assertEquals("""grep pattern="foo"""", shell("rg foo"))
        assertEquals("""grep pattern="a.b"""", shell("grep -F a.b -r src"))
        assertEquals("""grep pattern="timeout"""", shell("""sed -n '/timeout/p' $big"""))
    }

    @Test
    fun `listing files by name is answered by find or the map`() {
        assertEquals("outline", shell("""find . -name "*.kt""""))
        assertEquals("outline", shell("rg --files -g '*.kt'"))
        assertEquals("""find q="OrderService*"""", shell("""find src -name "OrderService*.kt""""))
        assertEquals("""find q="OrderService"""", shell("""find . -type f -iname OrderService.kt -not -path './build/*'"""))
        assertEquals("""usages name="OrderService"""", shell("""find . -name '*.kt' -exec grep -l OrderService {} +"""))
    }

    @Test
    fun `find that deletes or runs something on the files is no listing`() {
        assertEquals(null, shell("""find . -name "*.java" -delete"""))
        assertEquals(null, shell("""find src -name '*.kt' -exec sed -i 's/a/b/' {} +"""))
        assertEquals(null, shell("""find . -name '*.kt' -exec rm {} +"""))
    }

    @Test
    fun `whole-file reads are answered by outline`() {
        assertEquals("""outline target="$big"""", shell("cat $big"))
        assertEquals("""outline target="$big"""", shell("cat -n $big"))
        assertEquals("""outline target="$big"""", shell("cd src/main && cat kotlin/com/acme/shop/OrderService.kt"))
        assertEquals("""outline target="$big"""", shell("""sed -n '1,400p' $big"""))
        assertEquals("""outline target="$big"""", shell("""sed -n '10,200p' $big"""))
        assertEquals("""outline target="$big"""", shell("head -n 300 $big"))
        assertEquals("""outline target="$big"""", shell("head -300 $big"))
        assertEquals("""outline target="$big"""", shell("tail -n +50 $big"))
        assertEquals("""outline target="$big"""", shell("""bat $big"""))
        assertEquals("""outline target="$big"""", shell("""cat /repo/$big"""))
        assertEquals("""outline target="app/Thing.java"""", shell("cat app/Thing.java"))
    }

    @Test
    fun `the Read tool is answered like a whole-file cat`() {
        assertEquals("""outline target="$big"""", read("/repo/$big"))
        assertNull(read("/repo/$big", extra = mapOf("limit" to 100)))
        assertNull(read("/repo/$big", extra = mapOf("offset" to 120)))
        assertNull(read("/repo/$small"))
        assertNull(read("/repo/README.md"))
        assertNull(read("/repo/build/generated/Gen.kt"), "not in the index")
        assertNull(read("/elsewhere/Foo.kt"), "another repository")
    }

    @Test
    fun `short and partial reads are left alone`() {
        assertNull(shell("cat $small"))
        assertNull(shell("head -n 40 $big"))
        assertNull(shell("head $big"))
        assertNull(shell("tail -n 30 $big"))
        assertNull(shell("tail $big"))
        assertNull(shell("""sed -n '120,160p' $big"""))
        assertNull(shell("""sed -n '120p' $big"""))
        assertNull(shell("cat $big | head -50"))
        assertNull(shell("cat $big | grep -n foo"))
        assertNull(shell("cat $big > /tmp/copy.kt"))
    }

    @Test
    fun `commands that are not about source code are left alone`() {
        listOf(
            "./gradlew test", "git status", "git log --oneline | grep OrderService", "ls -la src", "echo hello", "codeloupe usages OrderService",
            "node run.mjs api get /x", "docker ps | grep app", "rg OrderService docs", """rg -t md foo""", "grep -n foo README.md", "cat README.md",
            "sed -i 's/a/b/' $big", "xargs grep foo", "git ls-files | xargs grep -n OrderService", "find . -name '*.md'", "rg --files",
            "python - <<'EOF'\ncat $big\nEOF", "mvn -q test", "cat", "rg", "grep", "grep foo", "ps aux | rg java",
        ).forEach { assertNull(shell(it), it) }
    }

    @Test
    fun `an unknown repository, a file outside the index or a variable path is left alone`() {
        assertNull(bash(posix, "rg OrderService src", "/other"), "daemon does not know /other")
        assertNull(shell("cat /repo/build/generated/Gen.kt"))
        assertNull(shell("cat \$FILE"))
        assertNull(shell("rg OrderService \$DIR"))
        assertNull(shell("cat ../outside/OrderService.kt"))
    }

    @Test
    fun `windows paths and PowerShell`() {
        val cwd = "C:/work/repo"
        fun win(command: String, tool: String = "Bash") = bash(windows, command, cwd, tool)?.call()
        assertEquals("""outline target="$big"""", win("""cat C:\work\repo\src\main\kotlin\com\acme\shop\OrderService.kt"""))
        assertEquals("""outline target="$big"""", win("cat C:/work/repo/$big"))
        assertEquals("""outline target="$big"""", win("cat /c/work/repo/$big"))
        assertEquals("""outline target="$big"""", win("""cat src\main\kotlin\com\acme\shop\OrderService.kt"""))
        assertEquals("""usages name="OrderService"""", win("""rg OrderService "C:\work\repo\src\main""""))
        assertEquals("""outline target="$big"""", win("""Get-Content src\main\kotlin\com\acme\shop\OrderService.kt""", "PowerShell"))
        assertNull(win("""Get-Content src\main\kotlin\com\acme\shop\OrderService.kt -TotalCount 40""", "PowerShell"))
        assertEquals("""usages name="OrderService"""", win("""Select-String -Path src\main\kotlin\**\*.kt -Pattern "OrderService"""", "PowerShell"))
        assertEquals("""grep pattern="timeout" ignoreCase=true""", win("""Get-ChildItem -Recurse -Filter *.kt | Select-String timeout""", "PowerShell"))
        assertNull(win("""Get-ChildItem -Recurse -Filter *.md | Select-String timeout""", "PowerShell"))
        assertNull(win("""cat D:\other\repo\src\Foo.kt"""))
    }

    @Test
    fun `each advice is a call with a shell form`() {
        val advice = bash(posix, """rg -i "select .* from" src/main""", "/repo")!!
        assertEquals("""grep pattern="select .* from" regex=true ignoreCase=true""", advice.call())
        assertEquals("codeloupe grep 'select .* from' --regex --ignore-case", advice.cli())
        assertEquals("codeloupe outline src/main/kotlin/com/acme/shop/OrderService.kt", bash(posix, "cat $big", "/repo")!!.cli())
        assertEquals("codeloupe find OrderService --kind fun", Advice("find", listOf("q" to "OrderService", "kind" to "fun"), "x", true).cli())
    }

    @Test
    fun `strength says whether the command plainly concerns source`() {
        assertTrue(bash(posix, "rg foo -g '*.kt'", "/repo")!!.strong)
        assertTrue(bash(posix, "grep -n foo $big", "/repo")!!.strong)
        assertEquals(false, bash(posix, "rg OrderService", "/repo")!!.strong)
        assertEquals(false, bash(posix, "grep -rn OrderService src", "/repo")!!.strong)
    }
}
