package codeloupe.git

import codeloupe.TestRepos
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalExcludesTest {
    private val home = TestRepos.tmpDir("excludes-home")
    private val common = TestRepos.tmpDir("excludes-repo").resolve(".git").createDirectories()

    private fun file(env: Map<String, String> = emptyMap()) = GlobalExcludes.file(common, env, home.toString())

    @Test
    fun `without any setting git reads ignore under the XDG config directory, which defaults to dot config in the home`() {
        assertEquals(home.resolve(".config/git/ignore"), file())
        val xdg = TestRepos.tmpDir("excludes-xdg")
        assertEquals(xdg.resolve("git/ignore"), file(mapOf("XDG_CONFIG_HOME" to xdg.toString())))
    }

    @Test
    fun `core excludesFile of the home config wins, a tilde is the home, and the repository config comes last`() {
        home.resolve(".gitconfig").writeText("[user]\n\tname = t\n[core]\n\texcludesFile = ~/ignore-global\n")
        assertEquals(home.resolve("ignore-global"), file())
        common.resolve("config").writeText("[core]\n    excludesfile = \"/etc/ignore repo\" # mine\n")
        assertEquals(Path.of("/etc/ignore repo"), file())
    }

    @Test
    fun `the config named by GIT_CONFIG_GLOBAL replaces the usual ones, and a section with the key on its own line is read`() {
        home.resolve(".gitconfig").writeText("[core]\n\texcludesFile = ~/not-this\n")
        val custom = TestRepos.tmpDir("excludes-custom").resolve("gitconfig")
        Files.writeString(custom, "[core] excludesFile = /srv/ignores\n[alias]\n\texcludesFile = /nope\n")
        assertEquals(Path.of("/srv/ignores"), file(mapOf("GIT_CONFIG_GLOBAL" to custom.toString())))
    }

    @Test
    fun `HOME of the environment is the home when it is set, as for git`() {
        val other = TestRepos.tmpDir("excludes-other-home")
        assertEquals(other.resolve(".config/git/ignore"), GlobalExcludes.file(common, mapOf("HOME" to other.toString()), home.toString()))
    }
}
