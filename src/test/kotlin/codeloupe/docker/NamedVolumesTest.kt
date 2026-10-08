package codeloupe.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NamedVolumesTest {
    @Test
    fun `named volumes are found in every spelling, binds and anonymous volumes are not`() {
        val args = listOf(
            "--rm", "-v", "pgdata:/var/lib/postgresql", "--volume=cache:/c:ro", "-vlogs:/l", "-v", "/host/dir:/d", "-v", "./rel:/r", "-v", "C:\\x:/win",
            "-v", "/anonymous", "--mount", "type=volume,source=mounted,target=/m", "--mount=type=bind,source=/b,target=/b",
            "--mount", "type=tmpfs,target=/t", "--mount", "src=short,dst=/s", "alpine", "ls",
        )
        assertEquals(listOf("pgdata", "cache", "logs", "mounted", "short"), NamedVolumes.of(args))
    }

    @Test
    fun `the same volume twice is created once`() {
        assertEquals(listOf("vol"), NamedVolumes.of(listOf("-v", "vol:/a", "-v", "vol:/b")))
    }

    @Test
    fun `codeloupe labels cannot be given by the caller`() {
        assertFailsWith<IllegalArgumentException> { OwnLabels.rejectOwn(listOf("--label", "codeloupe.workspace=other", "alpine")) }
        assertFailsWith<IllegalArgumentException> { OwnLabels.rejectOwn(listOf("-l", "codeloupe.task=x")) }
        assertFailsWith<IllegalArgumentException> { OwnLabels.rejectOwn(listOf("--label=codeloupe.repo=x")) }
        OwnLabels.rejectOwn(listOf("--label", "app=web", "alpine"))
        assertEquals(listOf("--label", "codeloupe.repo=R", "--label", "codeloupe.workspace=W", "--label", "codeloupe.task="), OwnLabels.flags(Ownership("R", "W")))
    }
}
