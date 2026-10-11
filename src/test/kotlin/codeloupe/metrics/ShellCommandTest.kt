package codeloupe.metrics

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellCommandTest {
    private fun label(command: String) = ShellCommand.of(command)

    @Test
    fun `search commands keep their flags and show what was passed as placeholders`() {
        assertEquals("rg -n <str> <path>", label("""rg -n "foo bar" src/main"""))
        assertEquals("rg -n <str> <path>", label("""cd /w && rg -n 'Foo' src/main/kotlin src/test"""))
        assertEquals("rg -C -n <n> <str> <path>", label("""rg -n -C 3 "x" ."""))
        assertEquals("rg -n <arg>", label("rg -n handler"))
        assertEquals("sed -n <str> <path>", label("""sed -n '1,50p' src/A.kt"""))
        assertEquals("cat <path>", label("cat src/A.kt | head -50"))
        assertEquals("ls -la", label("ls -la"))
    }

    @Test
    fun `git is grouped by subcommand and flags, whatever the directory options`() {
        assertEquals("git status", label("git status"))
        assertEquals("git diff --stat <path>", label("cd /w && git -C /w diff --stat origin/master...HEAD"))
        assertEquals("git diff <arg> <path>", label("git --no-pager diff HEAD~1 -- src/A.kt"))
        assertEquals("git log --oneline -<n>", label("git log --oneline -20"))
        assertEquals("git commit -m <str>", label("""git commit -m "TER-1 add it" """))
    }

    @Test
    fun `gradle is grouped by task and flag with the values dropped`() {
        assertEquals("gradlew :app:test --console --tests <str>", label("""./gradlew :app:test --tests "FooTest" --console=plain"""))
        assertEquals("gradlew test", label("JAVA_HOME=C:/jdk ./gradlew test"))
        assertEquals("gradlew test --no-daemon", label("export JAVA_HOME=C:/jdk && ./gradlew test --no-daemon 2>&1"))
        assertEquals("gradlew test --no-daemon", label("gradlew.bat test --no-daemon"))
    }

    @Test
    fun `heredocs are their own groups and a write is told from a script`() {
        assertEquals("heredoc write", label("cat > src/A.kt <<'EOF' class A EOF"))
        assertEquals("heredoc write", label("tee out.txt <<EOF text EOF"))
        assertEquals("heredoc write", label("cat <<'EOF' > notes.md # title EOF"))
        assertEquals("heredoc python", label("python3 - <<'PY' print(1) PY"))
        assertEquals("heredoc node", label("node - <<'JS' console.log(1) JS"))
        assertEquals("heredoc git commit", label("""git commit -m "${'$'}(cat <<'EOF' fix the thing EOF )" """))
        assertEquals("heredoc write", label("\$f = @' text '@; Set-Content a.txt \$f"))
    }

    @Test
    fun `a here-string, a shift and a redirect are no heredoc`() {
        assertEquals("grep <str>", label("""grep <<< "x""""))
        assertEquals("echo <n>", label("echo 1 << 2"))
        assertEquals("echo <str>", label("""echo "a" > out.txt"""))
    }

    @Test
    fun `scripts run by an interpreter are named by script and subcommand`() {
        assertEquals("node terrio.mjs docker up --build", label("node run/terrio.mjs docker up --build"))
        assertEquals("node -e <str>", label("""node -e "console.log(1)""""))
        assertEquals("python tool.py <path>", label("python3 tool.py data/in.csv"))
        assertEquals("node private-names.test.mjs --test", label("node --test tools/private-names.test.mjs"))
        assertEquals("node brain.mjs claim <arg>", label("node brain/brain.mjs claim TER-5"))
    }

    @Test
    fun `programs with two-level subcommands keep both`() {
        assertEquals("codeloupe metrics collect --since <n>", label("codeloupe metrics collect --since 2026-10-01"))
        assertEquals("codeloupe symbol <arg>", label("codeloupe symbol Foo"))
        assertEquals("docker compose up -d -f <path>", label("docker compose -f x.yml up -d"))
        assertEquals("gh pr view <n>", label("gh pr view 12"))
    }

    @Test
    fun `powershell cmdlets keep their parameters`() {
        assertEquals("Get-Content -Path -TotalCount <path> <n>", label("Get-Content -Path src/A.kt -TotalCount 40"))
        assertEquals("Get-ChildItem -Recurse <arg>", label("cd C:\\w; Get-ChildItem -Recurse src"))
    }

    @Test
    fun `only the first command of a chain counts and an empty one is named`() {
        assertEquals("git add -A <path>", label("git add -A src/ && git commit -m x"))
        assertEquals("(empty command)", label(""))
        assertEquals("cd <path>", label("cd /w && "))
    }

    @Test
    fun `a line that only sets the scene or prints a heading hands over to the next command`() {
        assertEquals("rg -n <str> <arg>", label("echo '--- search ---'; cd /w && rg -n 'x' src"))
        assertEquals("git status", label("""cd "C:/my dir"; UA="Mozilla/5.0 (X11; Linux)"; git status"""))
        assertEquals("echo <arg>", label("echo done"), "alone, an echo is the command")
        assertEquals("gradlew test", label("export JAVA_HOME=C:/jdk; ./gradlew test"))
    }

    @Test
    fun `a line cut off while it still sets the scene is named so`() {
        assertEquals("(command cut off)", label("cd /w && export JAVA_HOME=\"C:/Users/x/.jdks/jbr-25.0.3\" CODELOUPE_HOME=\"C:/Users/x/…"))
        assertEquals("cd <path>", label("cd /w"), "a whole line that is a cd is a cd")
    }

    @Test
    fun `lines the summary joined are told apart`() {
        assertEquals("rg -n <str> <arg>", label("cd /w rg -n 'x' src"))
        assertEquals("python tool.py", label("export PYTHONIOENCODING=utf-8 python tool.py"))
        assertEquals("git log --oneline", label("""cd "C:/my dir" git log --oneline"""))
    }

    @Test
    fun `loops and conditionals are named as such`() {
        assertEquals("shell loop", label("for i in 1 2 3; do rg x; done"))
        assertEquals("shell loop", label("while true; do sleep 1; done"))
        assertEquals("shell conditional", label("if [ -f a ]; then cat a; fi"))
    }
}
