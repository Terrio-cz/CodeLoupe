package codeloupe.docker

import codeloupe.config.WorkspacesConfig
import codeloupe.workspace.WorkspaceState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResourceClassifierTest {
    // The adoption rules a Terrio setup writes in config.json for today's leftovers.
    private val rules = WorkspacesConfig.parse(
        Json.parseToJsonElement(
            """{"workspaces": {"adoption": [
                 {"repo": "TerrioImporter", "match": "^terrio-ter-(\\d+)(?:[-_].*)?$", "workspace": "TER-$1", "task": "TER-$1", "kinds": ["container", "volume", "network", "image"]},
                 {"repo": "TerrioImporter", "match": "^terrio-importer-app:ter-(\\d+)(?:-.*)?$", "workspace": "TER-$1", "task": "TER-$1", "kinds": ["image"]},
                 {"repo": "TerrioImporter", "match": "[", "workspace": "broken"},
                 {"repo": "TerrioImporter", "workspace": "no pattern"}
               ]}}""",
        ).jsonObject,
    ).adoption

    private fun obj(kind: ResourceKind, name: String, labels: Map<String, String> = emptyMap(), state: String? = null) =
        DockerObject(kind, "id-$name", listOf(name), labels, state)

    private val states = mapOf(("terrioimporter" to "ter-420") to WorkspaceState.ACTIVE, ("terrioimporter" to "ter-321") to WorkspaceState.LANDED)
    private val classifier = ResourceClassifier(rules) { repo, workspace -> states[repo.lowercase() to workspace.lowercase()] }

    @Test
    fun `broken rules are dropped, the valid ones stay`() {
        assertEquals(2, rules.size)
    }

    @Test
    fun `today's Terrio leftovers are adopted into their workspaces`() {
        val entries = classifier.classify(
            listOf(
                obj(ResourceKind.CONTAINER, "terrio-ter-420-app-1", state = "exited"),
                obj(ResourceKind.VOLUME, "terrio-ter-420_terrio-postgres-data"),
                obj(ResourceKind.VOLUME, "terrio-ter-634"),
                obj(ResourceKind.NETWORK, "terrio-ter-321_default"),
                obj(ResourceKind.IMAGE, "terrio-importer-app:ter-321"),
                obj(ResourceKind.IMAGE, "terrio-importer-app:ter-210-pre"),
                obj(ResourceKind.IMAGE, "terrio-ter-181-master:base"),
            ),
        )
        assertEquals(entries.size, entries.count { it.ownership == OwnershipClass.ADOPTED })
        val byName = entries.associateBy { it.names.single() }
        assertEquals("TER-420", byName.getValue("terrio-ter-420-app-1").workspace)
        assertEquals("TER-420", byName.getValue("terrio-ter-420_terrio-postgres-data").workspace)
        assertEquals("TER-634", byName.getValue("terrio-ter-634").workspace)
        assertEquals("TER-321", byName.getValue("terrio-importer-app:ter-321").workspace)
        assertEquals("TER-210", byName.getValue("terrio-importer-app:ter-210-pre").workspace)
        assertEquals("TER-181", byName.getValue("terrio-ter-181-master:base").workspace)
        assertEquals("TerrioImporter", byName.getValue("terrio-ter-634").repo)
        assertEquals("TER-634", byName.getValue("terrio-ter-634").task)
        assertEquals("adoption rule 1 (terrio-ter-634)", byName.getValue("terrio-ter-634").via)
        assertEquals("adoption rule 2 (terrio-importer-app:ter-321)", byName.getValue("terrio-importer-app:ter-321").via)
        assertEquals(WorkspaceState.ACTIVE, byName.getValue("terrio-ter-420-app-1").workspaceState)
        assertEquals(WorkspaceState.LANDED, byName.getValue("terrio-ter-321_default").workspaceState)
        assertNull(byName.getValue("terrio-ter-634").workspaceState)
    }

    @Test
    fun `a compose project name is matched too`() {
        val container = DockerObject(ResourceKind.CONTAINER, "c1", listOf("db-1"), mapOf(DockerObject.COMPOSE_PROJECT to "terrio-ter-420"))
        val entry = classifier.classify(listOf(container)).single()
        assertEquals("TER-420", entry.workspace)
        assertEquals("adoption rule 1 (terrio-ter-420)", entry.via)
    }

    @Test
    fun `an image is not adopted through the compose project that built it unless a rule asks for that`() {
        val project = mapOf(DockerObject.COMPOSE_PROJECT to "terrio-ter-561")
        val shared = listOf("terrio-importer-app:aot", "terrio-importer-app:jdk25", "terrio-importer-app:jdk25c").map { DockerObject(ResourceKind.IMAGE, "i-$it", listOf(it), project) }
        val untagged = DockerObject(ResourceKind.IMAGE, "f0f0f0f0f0f0", emptyList(), project)
        val byName = DockerObject(ResourceKind.IMAGE, "b1", listOf("terrio-importer-app:ter-561-aot"), project)
        val entries = classifier.classify(shared + untagged + byName).associateBy { it.names.singleOrNull() ?: it.id }
        shared.forEach { assertEquals(OwnershipClass.UNOWNED, entries.getValue(it.names.single()).ownership, it.names.single()) }
        assertEquals(OwnershipClass.UNOWNED, entries.getValue("f0f0f0f0f0f0").ownership)
        // A name the rules match still adopts an image.
        assertEquals("TER-561", entries.getValue("terrio-importer-app:ter-561-aot").workspace)

        val optIn = ResourceClassifier(
            WorkspacesConfig.parse(
                Json.parseToJsonElement(
                    """{"workspaces":{"adoption":[{"repo":"R","match":"^terrio-ter-(\\d+)$","workspace":"TER-$1","kinds":["image"],"matchProject":true}]}}""",
                ).jsonObject,
            ).adoption,
        ) { _, _ -> null }
        val adopted = optIn.classify(shared + untagged)
        assertEquals(OwnershipClass.ADOPTED, adopted.map { it.ownership }.distinct().single())
        assertEquals("adoption rule 1 (terrio-ter-561)", adopted.first().via)
    }

    @Test
    fun `a rule can switch the project match off for containers`() {
        val rule = WorkspacesConfig.parse(
            Json.parseToJsonElement("""{"workspaces":{"adoption":[{"repo":"R","match":"^terrio-ter-(\\d+)$","workspace":"TER-$1","matchProject":false}]}}""").jsonObject,
        ).adoption
        val container = DockerObject(ResourceKind.CONTAINER, "c", listOf("db-1"), mapOf(DockerObject.COMPOSE_PROJECT to "terrio-ter-7"))
        assertEquals(OwnershipClass.UNOWNED, ResourceClassifier(rule) { _, _ -> null }.classify(listOf(container)).single().ownership)
    }

    @Test
    fun `labels beat adoption rules`() {
        val labelled = obj(ResourceKind.VOLUME, "terrio-ter-420", mapOf(Ownership.REPO to "Other", Ownership.WORKSPACE to "feature-x", Ownership.TASK to ""))
        val entry = classifier.classify(listOf(labelled)).single()
        assertEquals(OwnershipClass.OWNED, entry.ownership)
        assertEquals("feature-x", entry.workspace)
        assertEquals("labels", entry.via)
        assertNull(entry.task)
    }

    @Test
    fun `unlabelled resources no rule maps, the user's shared stack and foreign systems are only reported`() {
        val entries = classifier.classify(
            listOf(
                obj(ResourceKind.CONTAINER, "terrio-postgres"),
                obj(ResourceKind.VOLUME, "terrio-importer_terrio-postgres-data"),
                obj(ResourceKind.CONTAINER, "recserving-activations-fr-437-manager-1"),
                obj(ResourceKind.IMAGE, "terrio-importer-app:aot"),
                obj(ResourceKind.VOLUME, "trivy-cache-t149"),
                obj(ResourceKind.CONTAINER, "x", mapOf(Ownership.REPO to "R")),
            ),
        )
        assertEquals(OwnershipClass.UNOWNED, entries.map { it.ownership }.distinct().single())
        assertEquals(setOf(null), entries.map { it.workspace }.toSet())
    }

    @Test
    fun `a rule limited to some kinds ignores the others`() {
        val onlyVolumes = ResourceClassifier(WorkspacesConfig.parse(Json.parseToJsonElement("""{"workspaces":{"adoption":[{"repo":"R","match":"^x-(\\d+)","workspace":"W-$1","kinds":["volume"]}]}}""").jsonObject).adoption) { _, _ -> null }
        val entries = onlyVolumes.classify(listOf(obj(ResourceKind.VOLUME, "x-1"), obj(ResourceKind.CONTAINER, "x-1")))
        assertEquals(listOf(OwnershipClass.ADOPTED, OwnershipClass.UNOWNED), entries.map { it.ownership })
    }
}
