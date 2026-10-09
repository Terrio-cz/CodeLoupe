package codeloupe.secrets.imports

import codeloupe.secrets.SecretMeta
import codeloupe.secrets.SecretScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** A variable found in a folder takes that folder's scope; it must be flagged when it would hide a stored secret of a wider scope. */
class ShadowingTest {
    private fun meta(name: String, scope: SecretScope) = SecretMeta(name, scope.toString(), "manual", "2026-10-09T00:00:00Z")

    private val stored = Shadowing(
        listOf(meta("API_KEY", SecretScope.GLOBAL), meta("DB_URL", SecretScope.workspace("/work/ws")), meta("ONLY_REPO", SecretScope.repository("/work/ws/a"))),
    )

    @Test
    fun `a workspace or repository value hides a global one of the same name`() {
        assertEquals(listOf("global"), stored.of("API_KEY", SecretScope.workspace("/work/evil")))
        assertEquals(listOf("global"), stored.of("API_KEY", SecretScope.repository("/work/ws/b")))
    }

    @Test
    fun `a repository value hides the workspace value only inside that workspace`() {
        assertEquals(listOf("workspace:/work/ws"), stored.of("DB_URL", SecretScope.repository("/work/ws/b")))
        assertEquals(emptyList(), stored.of("DB_URL", SecretScope.repository("/work/ws2/b")))
        assertEquals(emptyList(), stored.of("DB_URL", SecretScope.repository("/work/ws")))
    }

    @Test
    fun `the occurrences that hide something are told apart from the rest`() {
        fun found(name: String, scope: SecretScope) = FoundVariable(name, java.nio.file.Path.of("/work/evil/.env"), SourceKind.DOTENV, scope, "1", 0, 10, "value-123456789")
        val hides = found("API_KEY", SecretScope.workspace("/work/evil"))
        val fine = found("OTHER", SecretScope.workspace("/work/evil"))
        assertEquals(listOf(hides), stored.hiding(listOf(hides, fine)))
    }

    @Test
    fun `a new name, the same scope, a wider scope and a narrower stored one hide nothing`() {
        assertEquals(emptyList(), stored.of("NEW_NAME", SecretScope.workspace("/work/x")))
        assertEquals(emptyList(), stored.of("API_KEY", SecretScope.GLOBAL))
        assertEquals(emptyList(), stored.of("ONLY_REPO", SecretScope.workspace("/work/ws")))
    }
}
