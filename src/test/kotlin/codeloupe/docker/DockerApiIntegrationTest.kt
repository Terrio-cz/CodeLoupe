package codeloupe.docker

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.WorkspacesConfig
import codeloupe.daemon.Daemon
import codeloupe.workspace.WorkspaceState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Against the local Docker Engine (Docker Desktop): `CODELOUPE_DOCKER_TESTS=1 ./gradlew test --tests '*DockerApiIntegrationTest'`. */
class DockerApiIntegrationTest {
    @Test
    fun `a volume made through CodeLoupe carries the labels and an existing one is never relabelled`() {
        val docker = DockerTestSupport.open("CODELOUPE_DOCKER_TESTS") ?: return
        try {
            val volume = "${docker.name}-vol"
            assertEquals(DockerApi.VolumeOutcome.CREATED, docker.api.createVolume(volume, docker.ownership))
            assertEquals(docker.ownership.labels(), docker.api.volumeLabels(volume)?.filterKeys { it.startsWith("codeloupe.") })
            assertEquals(DockerApi.VolumeOutcome.ALREADY_OURS, docker.api.createVolume(volume, docker.ownership))
            assertEquals(DockerApi.VolumeOutcome.EXISTS_OTHER, docker.api.createVolume(volume, docker.ownership.copy(task = "CLT-2")))
            // The refused call left the labels of the first one.
            assertEquals("CLT-1", docker.api.volumeLabels(volume)?.get(Ownership.TASK))
            docker.assertOwned(docker.api.volumes(), ResourceKind.VOLUME, volume)
        } finally {
            docker.cleanUp()
        }
    }

    @Test
    fun `the daemon reports owned, adopted and unowned resources and changes none of them`() {
        val repo = TestRepos.fixtureRepo("kotlin/sample")
        val workspace = repo.fileName.toString()
        // The labels name the fixture repository's main worktree, so the registry knows the workspace.
        val docker = DockerTestSupport.open("CODELOUPE_DOCKER_TESTS", repo = workspace, workspace = workspace) ?: return
        val adopted = "${docker.name}-adopted"
        try {
            docker.api.createVolume("${docker.name}-owned", docker.ownership)
            docker.createUnlabelledVolume(adopted)
            val before = docker.api.snapshot().associate { (it.kind to it.id) to it.labels }
            val port = ServerSocket(0).use { it.localPort }
            val settings = """{"workspaces":{"repos":["${repo.toString().replace('\\', '/')}"],
                "adoption":[{"repo":"$workspace","match":"^${docker.name}-adopted$","workspace":"$workspace","kinds":["volume"]}]}}"""
            val config = Config(TestRepos.tmpDir("home"), port, 60_000, 120_000, 512, null, workspaces = WorkspacesConfig.parse(Json.parseToJsonElement(settings).jsonObject))
            val daemon = Daemon.start(config)
            try {
                val response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/resources")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).build(), HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, response.statusCode(), response.body())
                val report = JsonFormat.json.decodeFromString(ResourceReport.serializer(), response.body())
                assertNotNull(report.engine)
                val owned = report.resources.single { it.names == listOf("${docker.name}-owned") }
                assertEquals(OwnershipClass.OWNED, owned.ownership)
                assertEquals(WorkspaceState.ACTIVE, owned.workspaceState)
                val mapped = report.resources.single { it.names == listOf(adopted) }
                assertEquals(OwnershipClass.ADOPTED, mapped.ownership)
                assertEquals(workspace, mapped.workspace)
                assertEquals(WorkspaceState.ACTIVE, mapped.workspaceState)
                assertTrue(report.resources.all { it.ownership != OwnershipClass.UNOWNED || it.workspace == null })
                // The adopted volume is still without labels, and nothing else in the Engine changed.
                assertEquals(before, docker.api.snapshot().associate { (it.kind to it.id) to it.labels }, "reading the inventory changed a resource")
                assertEquals(emptyMap(), docker.api.volumeLabels(adopted))
            } finally {
                daemon.stop()
            }
        } finally {
            docker.cleanUp()
            docker.removeVolume(adopted)
        }
    }
}
