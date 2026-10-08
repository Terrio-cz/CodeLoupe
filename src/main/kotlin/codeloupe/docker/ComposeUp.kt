package codeloupe.docker

import codeloupe.JsonFormat
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * `docker compose up` with the ownership labels on the containers, built images, volumes and networks of the project:
 * the resolved model gives the names, a generated override file adds the labels, and afterwards the Engine is asked
 * whether everything the project created carries them.
 */
class ComposeUp(
    private val cli: DockerCli,
    private val api: () -> DockerApi,
    private val note: (String) -> Unit,
    private val env: Map<String, String> = System.getenv(),
) {
    /**
     * [files] are compose files (default: `COMPOSE_FILE`, else the usual names in [dir]); [project] defaults to repo and
     * workspace; [extra] are the arguments of `up` (default `-d`); [envFiles] and [projectDirectory] are compose's own
     * `--env-file` and `--project-directory`. Answers the exit code, or [UNLABELED].
     */
    fun up(
        dir: Path,
        ownership: Ownership,
        files: List<String>,
        project: String?,
        profiles: List<String>,
        extra: List<String>,
        envFiles: List<String> = emptyList(),
        projectDirectory: String? = null,
    ): Int {
        val base = baseFiles(dir, files)
        val name = project ?: projectName(ownership)
        val global = envFiles.flatMap { listOf("--env-file", it) } + listOfNotNull(projectDirectory?.let { "--project-directory" }, projectDirectory)
        val scope = global + base.flatMap { listOf("-f", it) } + listOf("-p", name) + profiles.flatMap { listOf("--profile", it) }
        val config = cli.capture(listOf("compose") + scope + listOf("config", "--format", "json"), dir)
        if (config.exit != 0) return config.exit
        val override = ComposeOverride.build(JsonFormat.json.parseToJsonElement(config.stdout).jsonObject, ownership)
        val overrideFile = Files.createTempFile("codeloupe-compose-", ".yaml")
        try {
            // JSON is YAML, and compose reads it as such.
            Files.writeString(overrideFile, override.toString())
            val exit = cli.run(listOf("compose") + global + base.flatMap { listOf("-f", it) } + listOf("-f", overrideFile.toString(), "-p", name) +
                profiles.flatMap { listOf("--profile", it) } + listOf("up") + extra.ifEmpty { listOf("-d") }, dir)
            return if (exit == 0) verify(name, ownership) else exit
        } finally {
            Files.deleteIfExists(overrideFile)
        }
    }

    // Everything the Engine lists under the project must carry our labels; what the project only uses (external volumes) has no project label.
    private fun verify(project: String, ownership: Ownership): Int {
        val missing = api().let { it.containers() + it.volumes() + it.networks() }
            .filter { it.project == project && Ownership.of(it.labels) != ownership }
        missing.forEach { note("${it.kind.name.lowercase()} ${it.names.firstOrNull() ?: it.id} of project $project does not carry the CodeLoupe labels") }
        return if (missing.isEmpty()) 0 else UNLABELED
    }

    private fun baseFiles(dir: Path, given: List<String>): List<String> {
        if (given.isNotEmpty()) return given.map { dir.resolve(it).toString() }
        env["COMPOSE_FILE"]?.takeIf { it.isNotBlank() }?.let { value ->
            val separator = env["COMPOSE_PATH_SEPARATOR"]?.takeIf { it.isNotEmpty() } ?: File.pathSeparator
            return value.split(separator).filter { it.isNotBlank() }.map { dir.resolve(it).toString() }
        }
        val found = DEFAULT_FILES.map { dir.resolve(it) }.firstOrNull { Files.isRegularFile(it) }
            ?: throw IllegalArgumentException("no compose file in $dir (looked for ${DEFAULT_FILES.joinToString(", ")}); give one with -f")
        return listOf(found.toString())
    }

    companion object {
        const val UNLABELED = 3
        private val DEFAULT_FILES = listOf("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml")

        /** A compose project name (lower case letters, digits, `-` and `_`) from the workspace. */
        fun projectName(ownership: Ownership): String =
            "${ownership.repo}-${ownership.workspace}".lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-', '_')
    }
}
