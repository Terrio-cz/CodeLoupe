package codeloupe.cli

import codeloupe.TestRepos
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A deploy an upgrade replaced stays executable beside the data, and that is how an old daemon takes the port again (CL-260). */
class StaleDeploysTest {
    private val home = TestRepos.tmpDir("stale-deploys")

    /** An install as `installDist` lays one out: the jar under `lib`, the launcher under `bin`. */
    private fun deploy(name: String, version: String): Path {
        val dir = Files.createDirectories(home.resolve(name))
        Files.createDirectories(dir.resolve("lib"))
        Files.writeString(dir.resolve("lib").resolve("codeloupe-$version.jar"), "not really a jar")
        Files.createDirectories(dir.resolve("bin"))
        Files.writeString(dir.resolve("bin").resolve("codeloupe.bat"), "@echo off")
        return dir
    }

    @Test
    fun `an obsolete deploy is moved out of the way and the running one is left alone`() {
        val old = deploy("app2", "0.1.0")
        val older = deploy("app.old-0.1.0", "0.1.0")
        val ours = deploy("app", "9.9.9")
        Files.createDirectories(home.resolve("repos")) // not a deploy: untouched whatever its name

        val said = StaleDeploys.retire(home, runningFrom = ours.resolve("lib").resolve("codeloupe-9.9.9.jar").toString())

        assertEquals(2, said.size, said.toString())
        assertContains(said.joinToString("\n"), "retired the obsolete deploy")
        assertFalse(Files.exists(old), "the obsolete deploy must be gone from where a launcher search finds it")
        assertFalse(Files.exists(older))
        assertTrue(Files.exists(ours.resolve("lib").resolve("codeloupe-9.9.9.jar")), "the install we run from must stay")
        assertTrue(Files.exists(home.resolve(StaleDeploys.RETIRED).resolve("app2").resolve("lib").resolve("codeloupe-0.1.0.jar")))
        assertTrue(Files.exists(home.resolve("repos")))
    }

    @Test
    fun `a second retirement keeps the first`() {
        deploy("app2", "0.1.0")
        StaleDeploys.retire(home, runningFrom = null)
        deploy("app2", "0.1.1")
        StaleDeploys.retire(home, runningFrom = null)

        val retired = home.resolve(StaleDeploys.RETIRED)
        assertTrue(Files.exists(retired.resolve("app2").resolve("lib").resolve("codeloupe-0.1.0.jar")))
        assertTrue(Files.exists(retired.resolve("app2-2").resolve("lib").resolve("codeloupe-0.1.1.jar")))
    }

    @Test
    fun `a directory that is not an install is left where it is`() {
        Files.createDirectories(home.resolve("appdata").resolve("lib"))
        assertEquals(emptyList(), StaleDeploys.retire(home, runningFrom = null))
        assertTrue(Files.exists(home.resolve("appdata")))
    }
}
