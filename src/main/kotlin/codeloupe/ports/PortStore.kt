package codeloupe.ports

import codeloupe.JsonFormat
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The recorded allocations, in a small file (`<home>/ports.json`) so they survive a restart. */
class PortStore(private val file: Path) {
    private val items = ArrayList<PortAllocation>()

    init {
        runCatching { items.addAll(JsonFormat.json.decodeFromString(SERIALIZER, Files.readString(file))) }
    }

    @Synchronized
    fun all(): List<PortAllocation> = items.toList()

    @Synchronized
    fun find(repo: String, workspace: String, name: String): PortAllocation? = items.firstOrNull { same(it, repo, workspace) && it.name.equals(name, ignoreCase = true) }

    @Synchronized
    fun add(allocation: PortAllocation) {
        items += allocation
        save()
    }

    /** Frees the ports of a workspace: the one called [name], or all when [name] is null. Answers how many. */
    @Synchronized
    fun free(repo: String, workspace: String, name: String? = null): Int {
        val before = items.size
        items.removeAll { same(it, repo, workspace) && (name == null || it.name.equals(name, ignoreCase = true)) }
        if (items.size != before) save()
        return before - items.size
    }

    private fun same(a: PortAllocation, repo: String, workspace: String) = a.repo.equals(repo, ignoreCase = true) && a.workspace.equals(workspace, ignoreCase = true)

    private fun save() {
        runCatching {
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(temp, JsonFormat.json.encodeToString(SERIALIZER, items.toList()))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        val SERIALIZER = ListSerializer(PortAllocation.serializer())
    }
}
