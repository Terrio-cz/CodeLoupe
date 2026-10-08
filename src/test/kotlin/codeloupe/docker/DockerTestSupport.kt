package codeloupe.docker

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests create real Docker resources, so they run only when asked for (`CODELOUPE_DOCKER_TESTS=1` for the
 * Engine API ones, `CODELOUPE_DOCKER_CLI_TESTS=1` for the ones that also run the `docker` client) and only when an Engine
 * answers. Everything they create is named `cltest-<random>…` and carries the ownership labels of [ownership];
 * [cleanUp] removes exactly the `cltest-` resources that carry them, and nothing else.
 */
class DockerTestSupport(val api: DockerApi, repo: String = "cltest-repo", workspace: String? = null) {
    val name = "cltest-" + UUID.randomUUID().toString().take(8)
    val ownership = Ownership(repo, workspace ?: name, "CLT-1")
    private val http = DockerHttp(DockerEndpoint.firstReachable(DockerEndpoint.candidates()))

    /** Removes the test's resources, containers first. Anything that does not carry the labels and a `cltest-` name stays. */
    fun cleanUp() {
        mine(api.containers()).forEach { http.request("DELETE", "/containers/${it.id}?force=1&v=1") }
        mine(api.networks()).forEach { http.request("DELETE", "/networks/${it.id}") }
        mine(api.volumes()).forEach { http.request("DELETE", "/volumes/${it.names.single()}") }
        mine(api.images()).forEach { http.request("DELETE", "/images/${it.id}?force=1") }
    }

    /** Removes the `cltest-` volume [volume] that was made without labels (to be adopted). */
    fun removeVolume(volume: String) {
        check(volume.startsWith("cltest-")) { "refusing to remove $volume" }
        http.request("DELETE", "/volumes/$volume")
    }

    /** A volume without any label, the way Docker makes one for a plain `docker run -v`. */
    fun createUnlabelledVolume(volume: String) {
        check(volume.startsWith("cltest-")) { "refusing to create $volume" }
        assertEquals(201, http.request("POST", "/volumes/create", """{"Name":"$volume"}""".toByteArray()).status)
    }

    fun mine(objects: List<DockerObject>) = objects.filter {
        Ownership.of(it.labels) == ownership && (it.names.isEmpty() || it.names.all { n -> n.startsWith("cltest-") })
    }

    fun assertOwned(objects: List<DockerObject>, kind: ResourceKind, vararg names: String) {
        for (wanted in names) {
            val found = objects.firstOrNull { it.kind == kind && wanted in it.names }
            assertTrue(found != null, "$kind $wanted not found")
            assertEquals(ownership, Ownership.of(found.labels), "$kind $wanted")
        }
    }

    companion object {
        /** The support for a test that needs [flag], or null when it is not asked for or no Engine answers. */
        fun open(flag: String, repo: String = "cltest-repo", workspace: String? = null): DockerTestSupport? {
            if (System.getenv(flag) != "1") return null
            return runCatching { DockerTestSupport(DockerApi.connect(), repo, workspace) }.getOrNull()
        }
    }
}
