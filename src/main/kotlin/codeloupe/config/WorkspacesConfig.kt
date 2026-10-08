package codeloupe.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The workspace registry, from `config.json`:
 * `"workspaces": { "abandonedDays": 14, "repos": [ { "path": "<repo>", "roots": ["<directory holding its worktrees>"] } ] }`.
 * A repository is also known when a tracker lists it in `repos`; `<repo name>-worktrees` next to the repository is
 * always a root. A repo entry may be just the path. `adoption` maps resources without CodeLoupe labels to workspaces,
 * see [AdoptionRule]; `reconcile` configures the cleanup of released workspaces, see [ReconcileConfig]; `ports` gives the range of the port registry, see [PortsConfig].
 * `gradleUserHome` names the Gradle user home whose `daemon/` logs say where an idle Gradle daemon last built, when it is neither
 * `GRADLE_USER_HOME` of the daemon nor `~/.gradle`. `recentScanMs` is how long the read-only routes (`/workspaces`, `/resources`, `/reconcile`, `/ports`)
 * share one registry scan; 0 makes each read its own.
 */
data class WorkspacesConfig(
    val repos: List<Repo> = emptyList(),
    /** A workspace nobody touched for this long and whose work is not on the default branch counts as abandoned. */
    val abandonedDays: Int = 14,
    val adoption: List<AdoptionRule> = emptyList(),
    val reconcile: ReconcileConfig = ReconcileConfig(),
    val ports: PortsConfig = PortsConfig(),
    val gradleUserHome: String? = null,
    val recentScanMs: Long = 2_000,
) {
    data class Repo(val path: String, val roots: List<String> = emptyList())

    companion object {
        fun parse(file: JsonObject): WorkspacesConfig {
            val section = file["workspaces"] as? JsonObject ?: return WorkspacesConfig()
            val repos = (section["repos"] as? JsonArray).orEmpty().mapNotNull { entry ->
                when (entry) {
                    is JsonPrimitive -> text(entry)?.let { Repo(it) }
                    is JsonObject -> text(entry["path"])?.let { Repo(it, strings(entry["roots"])) }
                    else -> null
                }
            }
            val adoption = (section["adoption"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(AdoptionRule::parse) }
            return WorkspacesConfig(repos, (section["abandonedDays"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 } ?: 14, adoption, ReconcileConfig.parse(section["reconcile"] as? JsonObject), PortsConfig.parse(section["ports"] as? JsonObject), text(section["gradleUserHome"]), (section["recentScanMs"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it >= 0 } ?: 2_000)
        }

        private fun text(element: Any?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

        private fun strings(element: Any?): List<String> = (element as? JsonArray).orEmpty().mapNotNull(::text)
    }
}
