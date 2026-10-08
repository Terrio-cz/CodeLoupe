package codeloupe.docker

import codeloupe.TestRepos
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose, build and run through the `docker` client against a fixture compose project named `cltest-<random>`:
 * `CODELOUPE_DOCKER_CLI_TESTS=1 ./gradlew test --tests '*DockerCliIntegrationTest'`. The fixture uses an image that is
 * normally present on a developer machine (the JDK base of the project's own bundle build) so nothing is pulled.
 */
class DockerCliIntegrationTest {
    private val base = System.getenv("CODELOUPE_DOCKER_TEST_IMAGE") ?: "eclipse-temurin:25-jre-alpine"

    private fun fixture(name: String): Path = TestRepos.tmpDir("compose").also {
        it.resolve("compose.yaml").writeText(
            """
            services:
              web:
                image: $base
                command: ["sleep", "120"]
                volumes: ["data:/data"]
                networks: [back]
              app:
                build: .
                command: ["sleep", "120"]
            volumes:
              data: {}
            networks:
              back: {}
            """.trimIndent(),
        )
        it.resolve("Dockerfile").writeText("FROM $base\n")
    }

    @Test
    fun `compose up labels containers, the built image, the volume and both networks`() {
        val docker = DockerTestSupport.open("CODELOUPE_DOCKER_CLI_TESTS") ?: return
        val notes = ArrayList<String>()
        try {
            val dir = fixture(docker.name)
            val exit = ComposeUp(DockerCli(), { docker.api }, notes::add).up(dir, docker.ownership, emptyList(), docker.name, emptyList(), listOf("-d"))
            assertEquals(0, exit, notes.toString())
            val all = docker.api.snapshot().filter { it.project == docker.name || docker.name in it.names.joinToString() }
            docker.assertOwned(all, ResourceKind.CONTAINER, "${docker.name}-web-1", "${docker.name}-app-1")
            docker.assertOwned(all, ResourceKind.VOLUME, "${docker.name}_data")
            docker.assertOwned(all, ResourceKind.NETWORK, "${docker.name}_back", "${docker.name}_default")
            docker.assertOwned(docker.api.images(), ResourceKind.IMAGE, "${docker.name}-app:latest")
            assertTrue(notes.isEmpty(), notes.toString())
        } finally {
            docker.cleanUp()
        }
    }

    @Test
    fun `compose up takes an env file and a project directory from elsewhere, as the Terrio task stacks do`() {
        val docker = DockerTestSupport.open("CODELOUPE_DOCKER_CLI_TESTS") ?: return
        val notes = ArrayList<String>()
        try {
            val project = TestRepos.tmpDir("compose-project")
            project.resolve("compose.yaml").writeText(
                """
                services:
                  web:
                    image: ${'$'}{CLT_IMAGE}
                    command: ["sleep", "120"]
                    volumes: ["data:/data"]
                volumes:
                  data: {}
                """.trimIndent(),
            )
            val elsewhere = TestRepos.tmpDir("compose-env")
            val envFile = elsewhere.resolve("task.env").also { it.writeText("CLT_IMAGE=$base\n") }
            val exit = ComposeUp(DockerCli(), { docker.api }, notes::add).up(
                elsewhere, docker.ownership, listOf(project.resolve("compose.yaml").toString()), docker.name, emptyList(), listOf("-d"),
                envFiles = listOf(envFile.toString()), projectDirectory = project.toString(),
            )
            assertEquals(0, exit, notes.toString())
            val all = docker.api.snapshot().filter { it.project == docker.name }
            docker.assertOwned(all, ResourceKind.CONTAINER, "${docker.name}-web-1")
            docker.assertOwned(all, ResourceKind.VOLUME, "${docker.name}_data")
            assertTrue(notes.isEmpty(), notes.toString())
        } finally {
            docker.cleanUp()
        }
    }

    @Test
    fun `run labels the container and creates the named volume labelled`() {
        val docker = DockerTestSupport.open("CODELOUPE_DOCKER_CLI_TESTS") ?: return
        val notes = ArrayList<String>()
        try {
            val volume = "${docker.name}-vol"
            val exit = OwnedRun(DockerCli(), { docker.api }, notes::add)
                .run(TestRepos.tmpDir("run"), docker.ownership, listOf("--name", "${docker.name}-box", "-v", "$volume:/d", base, "sh", "-c", "echo hi > /d/x"))
            assertEquals(0, exit, notes.toString())
            docker.assertOwned(docker.api.containers(), ResourceKind.CONTAINER, "${docker.name}-box")
            docker.assertOwned(docker.api.volumes(), ResourceKind.VOLUME, volume)
        } finally {
            docker.cleanUp()
        }
    }

    @Test
    fun `build labels the image and the check passes`() {
        val docker = DockerTestSupport.open("CODELOUPE_DOCKER_CLI_TESTS") ?: return
        val notes = ArrayList<String>()
        try {
            val dir = fixture(docker.name)
            val exit = OwnedBuild(DockerCli(), { docker.api }, notes::add).build(dir, docker.ownership, listOf("-t", "${docker.name}-img:1", "."))
            assertEquals(0, exit, notes.toString())
            docker.assertOwned(docker.api.images(), ResourceKind.IMAGE, "${docker.name}-img:1")
        } finally {
            docker.cleanUp()
        }
    }
}
