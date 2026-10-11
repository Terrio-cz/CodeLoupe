package codeloupe.brain

import codeloupe.platform.Sha1
import java.nio.file.Files
import java.nio.file.Path

/**
 * A fingerprint of the places a brain keeps its data in: name, size and modification time of every file under them. It reads no file, so
 * checking it before a query costs a few hundred `stat` calls; the index is rebuilt when it differs from the one the index was built at.
 */
object BrainSignature {
    fun of(paths: BrainPaths): String {
        val parts = ArrayList<String>()
        for (dir in listOf(paths.facts, paths.tasks, paths.machineFacts)) tree(dir, parts)
        for (file in listOf(paths.feedback, paths.config)) one(file, parts)
        return Sha1.hex(parts.sorted().joinToString("\n"))
    }

    private fun tree(dir: String, out: MutableList<String>) {
        if (dir.isEmpty()) return
        val root = Path.of(dir)
        if (!Files.isDirectory(root)) return
        runCatching {
            Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }.forEach { one(it.toString(), out) } }
        }
    }

    private fun one(file: String, out: MutableList<String>) {
        if (file.isEmpty()) return
        runCatching {
            val path = Path.of(file)
            val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
            out += "$file|${attrs.size()}|${attrs.lastModifiedTime().toMillis()}"
        }
    }
}
