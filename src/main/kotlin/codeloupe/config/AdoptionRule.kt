package codeloupe.config

import codeloupe.docker.DockerObject
import codeloupe.docker.Ownership
import codeloupe.docker.ResourceKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Maps a Docker resource that carries no CodeLoupe labels to the workspace it belongs to, by its name:
 * `{ "repo": "TerrioImporter", "match": "^terrio-ter-(\\d+)(?:[-_].*)?$", "kinds": ["container", "volume"], "workspace": "TER-$1", "task": "TER-$1" }`.
 * [match] is tried (case-insensitively) against each name of the resource and, see [matchProject], the compose project it belongs to;
 * `$1`… in [workspace] and [task] stand for the groups of the match. Adoption is only this mapping; nothing on the
 * Docker side is changed.
 */
data class AdoptionRule(
    val repo: String,
    val match: Regex,
    val workspace: String,
    val task: String = "",
    /** Empty: every kind. */
    val kinds: Set<ResourceKind> = emptySet(),
    /**
     * Whether [match] is also tried against the compose project of the resource. Null: yes for containers, volumes and
     * networks, no for images. An image carries the project that built it, but images get re-tagged and shared between
     * tasks (`aot`, `jdk25`), so the project alone does not make one a task's leftover; a rule has to ask for it.
     */
    val matchProject: Boolean? = null,
) {
    /** A resource mapped to a workspace, and the name (or compose project) that the pattern matched. */
    data class Adoption(val ownership: Ownership, val matched: String)

    fun apply(resource: DockerObject): Adoption? {
        if (kinds.isNotEmpty() && resource.kind !in kinds) return null
        val byProject = matchProject ?: (resource.kind != ResourceKind.IMAGE)
        for (name in resource.names + listOfNotNull(resource.project.takeIf { byProject })) {
            val found = match.find(name) ?: continue
            val workspace = expand(workspace, found).takeIf { it.isNotBlank() } ?: continue
            return Adoption(Ownership(repo, workspace, expand(task, found)), name)
        }
        return null
    }

    private fun expand(template: String, found: MatchResult): String =
        GROUP.replace(template) { found.groupValues.getOrNull(it.groupValues[1].toInt()).orEmpty() }.trim()

    companion object {
        private val GROUP = Regex("\\$(\\d+)")

        /** The rule written as JSON, null when it lacks a repo, a usable pattern or a workspace. */
        fun parse(entry: JsonObject): AdoptionRule? {
            fun text(key: String) = (entry[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            val repo = text("repo") ?: return null
            val match = text("match")?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() } ?: return null
            val workspace = text("workspace") ?: return null
            val kinds = (entry["kinds"] as? JsonArray).orEmpty().mapNotNull { k ->
                (k as? JsonPrimitive)?.content?.let { name -> ResourceKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            }.toSet()
            return AdoptionRule(repo, match, workspace, text("task").orEmpty(), kinds, (entry["matchProject"] as? JsonPrimitive)?.booleanOrNull)
        }
    }
}
