package codeloupe.secrets.imports

import codeloupe.config.EnvImportConfig
import codeloupe.config.ImportRoot
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/**
 * Walks the configured roots for files that hold environment variables and reads them. Values stay in the returned
 * [FoundVariable]s (memory only); the counters and the excluded folders carry no text of any file. [ownHome] (the
 * daemon's own state, including its vault) is never read.
 */
class EnvScanner(private val config: EnvImportConfig, private val ownHome: Path, private val includeExcluded: Boolean = false) {
    class Result(
        val found: List<FoundVariable>,
        /** Folders kept out because they belong to a system the guardrails exclude; listed, not entered. */
        val excluded: List<Path>,
        val filesRead: Int,
        val unreadable: Int,
        /** Names the store cannot hold, empty values and variables that already are references. */
        val invalidNames: Int,
        val empty: Int,
        val references: Int,
        /** SHA-256 of every file read, so a later rewrite can tell the file is still the one that was scanned. */
        val fileHashes: Map<Path, String>,
    )

    private class Tally {
        val found = mutableListOf<FoundVariable>()
        val excluded = mutableListOf<Path>()
        var filesRead = 0
        var unreadable = 0
        var invalidNames = 0
        var empty = 0
        var references = 0
        val hashes = mutableMapOf<Path, String>()
    }

    fun scan(roots: List<ImportRoot> = config.roots): Result {
        val tally = Tally()
        val own = ownHome.toAbsolutePath().normalize()
        roots.forEach { root -> scanRoot(root.copy(path = root.path.toAbsolutePath().normalize()), own, tally) }
        return Result(tally.found.distinctBy { it.id }, tally.excluded.distinct(), tally.filesRead, tally.unreadable, tally.invalidNames, tally.empty, tally.references, tally.hashes)
    }

    private fun scanRoot(root: ImportRoot, own: Path, tally: Tally) {
        if (!Files.exists(root.path)) return
        if (Files.isRegularFile(root.path)) return read(root, root.path, tally)
        val depth = if (root.kind == ImportRoot.Kind.HOME) HOME_DEPTH else MAX_DEPTH
        Files.walkFileTree(root.path, EnumSet.noneOf(FileVisitOption::class.java), depth, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == root.path) return FileVisitResult.CONTINUE
                val name = dir.fileName.toString()
                return when {
                    dir.startsWith(own) || name.lowercase() in PRUNE || (root.kind == ImportRoot.Kind.HOME && name.lowercase() in CLAUDE_STATE) -> FileVisitResult.SKIP_SUBTREE
                    !includeExcluded && excluded(name) -> FileVisitResult.SKIP_SUBTREE.also { tally.excluded.add(dir) }
                    else -> FileVisitResult.CONTINUE
                }
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && sourceKind(root, file) != null) read(root, file, tally)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                tally.unreadable++
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun excluded(folder: String): Boolean {
        val name = folder.lowercase()
        return config.exclude.any { word -> Regex("(^|[^a-z0-9])${Regex.escape(word)}[0-9]*($|[^a-z0-9])").containsMatchIn(name) }
    }

    private fun read(root: ImportRoot, file: Path, tally: Tally) {
        val kind = sourceKind(root, file) ?: return
        val limit = if (kind == SourceKind.DOTENV || kind == SourceKind.DOCKER_ENV) MAX_ENV_BYTES else MAX_JSON_BYTES
        val bytes = try {
            if (Files.size(file) > limit) return run { tally.unreadable++ }
            Files.readAllBytes(file)
        } catch (e: IOException) {
            return run { tally.unreadable++ }
        }
        tally.filesRead++
        tally.hashes[file] = ValueFingerprint.sha256(bytes)
        val text = String(bytes, StandardCharsets.UTF_8)
        val scope = ScopeSuggester.suggest(root, file)
        val raw = if (kind == SourceKind.DOTENV || kind == SourceKind.DOCKER_ENV) {
            DotenvParser.parse(text).map { FoundVariable(it.name, file, kind, scope, "line ${it.line}", it.start, it.end, it.value) }
        } else {
            val sites = JsonEnvSites.find(text) ?: return run { tally.unreadable++ }
            sites.map { FoundVariable(it.name, file, kind, scope, it.path, it.start, it.end, it.value) }
        }
        for (variable in raw) {
            when {
                !NAME.matches(variable.name) -> tally.invalidNames++
                variable.value.isEmpty() -> tally.empty++
                variable.reference -> tally.references++
                else -> tally.found += variable
            }
        }
    }

    private fun sourceKind(root: ImportRoot, file: Path): SourceKind? {
        val name = file.fileName.toString().lowercase()
        val parent = file.parent?.fileName?.toString()?.lowercase().orEmpty()
        return when {
            name == ".claude.json" || (root.path == file && name.startsWith(".claude") && name.endsWith(".json")) -> SourceKind.CLAUDE_JSON
            name == ".mcp.json" || name == "mcp.json" && parent.startsWith(".claude") -> SourceKind.MCP_CONFIG
            name.startsWith("settings") && name.endsWith(".json") && parent.startsWith(".claude") -> SourceKind.CLAUDE_SETTINGS
            isEnvFile(name) -> if (dockerContext(file)) SourceKind.DOCKER_ENV else SourceKind.DOTENV
            else -> null
        }
    }

    private fun isEnvFile(name: String): Boolean {
        if (name == ".env" || name.endsWith(".env")) return true
        return name.startsWith(".env.") && name.substringAfterLast('.') !in TEMPLATE_SUFFIXES
    }

    private fun dockerContext(file: Path): Boolean {
        val dir = file.parent ?: return false
        if (file.fileName.toString().contains("docker", ignoreCase = true) || dir.fileName.toString().contains("docker", ignoreCase = true)) return true
        return COMPOSE.any { Files.exists(dir.resolve(it)) }
    }

    companion object {
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private const val MAX_DEPTH = 8
        private const val HOME_DEPTH = 2
        private const val MAX_ENV_BYTES = 1L shl 20
        private const val MAX_JSON_BYTES = 64L shl 20
        private val TEMPLATE_SUFFIXES = setOf("example", "sample", "template", "dist", "tpl", "defaults", "schema")
        private val COMPOSE = listOf("docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml")
        private val PRUNE = setOf(
            "node_modules", ".git", ".gradle", "build", "dist", "out", "target", ".idea", ".venv", "venv", "__pycache__", ".next", ".cache",
            "coverage", ".journal",
        )
        private val CLAUDE_STATE = setOf("projects", "plugins", "shell-snapshots", "todos", "statsig", "file-history", "ide", "telemetry")
    }
}
