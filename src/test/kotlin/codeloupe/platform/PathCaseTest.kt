package codeloupe.platform

import codeloupe.TestRepos
import codeloupe.uiapi.WorktreeId
import codeloupe.workspace.OrphanDirs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PathCaseTest {
    @Test
    fun `Windows and macOS ignore case, Linux does not`() {
        assertEquals(true, PathCase.isInsensitive("Windows 11"))
        assertEquals(true, PathCase.isInsensitive("Mac OS X"))
        assertEquals(false, PathCase.isInsensitive("Linux"))
        assertEquals(false, PathCase.isInsensitive("FreeBSD"))
        assertEquals(PathCase.isInsensitive(System.getProperty("os.name")), PathCase.insensitive)
    }

    @Test
    fun `a path is folded only where the file system ignores case`() {
        assertEquals("/src/foo/bar.kt", PathCase.fold("/Src/Foo/Bar.kt", insensitive = true))
        assertEquals("/Src/Foo/Bar.kt", PathCase.fold("/Src/Foo/Bar.kt", insensitive = false))
    }

    @Test
    fun `two spellings of one worktree path give one id where case is ignored and two where it is not`() {
        val root = TestRepos.tmpDir("case").toString()
        val upper = root + "/Work"
        val lower = root + "/work"
        assertEquals(PathCase.insensitive, WorktreeId.of(upper) == WorktreeId.of(lower))
        assertEquals(PathCase.insensitive, WorktreeId.contains(upper, "$lower/src"))
        assertEquals(WorktreeId.of(upper), WorktreeId.of(upper))
        assertNotEquals(WorktreeId.of(upper), WorktreeId.of("$upper-other"))
    }

    @Test
    fun `the key of a worktree directory follows the same rule`() {
        val root = TestRepos.tmpDir("case-key")
        val key = OrphanDirs.key(root.resolve("Missing"))
        assertEquals(PathCase.insensitive, key == OrphanDirs.key(root.resolve("missing")))
    }
}
