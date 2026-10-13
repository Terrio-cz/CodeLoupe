package codeloupe.brain

import codeloupe.config.WorkspacesConfig
import codeloupe.taskcode.DispatchScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** What a brain workspace's `workspace.json` says about its tasks: the tracker project their ids carry and the repository they land in. */
object WorkspaceTasks {
    private val PROJECT = Regex("^\\W*([A-Za-z][A-Za-z0-9_]*)-")

    /** The scope of [root] when it lies in a registered workspace with a brain that names its task ids; null otherwise. */
    fun scopeOf(config: WorkspacesConfig, root: String): DispatchScope? {
        val workspace = BrainRoot.at(config, root) ?: return null
        val file = runCatching { Json.parseToJsonElement(Files.readString(workspace.path.resolve("brain").resolve("workspace.json"))).jsonObject }.getOrNull() ?: return null
        val project = (text(file, "taskIdPattern") ?: text(file, "taskPrefix"))?.let { PROJECT.find(it)?.groupValues?.get(1) }?.uppercase() ?: return null
        val repo = text(file, "canonicalRepo")?.takeIf { runCatching { Files.isDirectory(Path.of(it)) }.getOrDefault(false) }
        return DispatchScope(listOf(project), repo)
    }

    private fun text(file: JsonObject, key: String): String? = (file[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
