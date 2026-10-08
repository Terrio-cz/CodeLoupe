package codeloupe.tools

import codeloupe.repo.Registry
import codeloupe.secrets.SecretAccess
import codeloupe.secrets.SecretReport
import codeloupe.secrets.SecretStore

/**
 * `env`: which secrets and variables the store holds for a workspace and repository, by name, scope, source and last use.
 * It has no value to show: a value reaches a process through `codeloupe env run` or the token-guarded `/env/values` of the
 * daemon, never through a tool an agent calls.
 */
class EnvTool(private val access: SecretAccess) : Tool {
    override val name = "env"
    override val description = "Names of the environment variables and secrets the CodeLoupe store holds for a workspace and repository — " +
        "scope (global < workspace < repository, the narrowest wins), source, created, rotated, last use and by what, and ROTATE on a name older than the configured age. Never a value: " +
        "start a process with them through `codeloupe env run --workspace <w> --repo <r> -- <command>`. all=true lists every scope."
    override val properties = Schema.properties(
        "workspace" to Schema.string("Workspace folder or id the caller works in"),
        "repository" to Schema.string("Repository the caller works in"),
        "all" to Schema.boolean("Every stored name of every scope, not only those that apply"),
    )
    override val required = emptyList<String>()
    override val needsRoot = false

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val store = access.store ?: return "no secret store: ${access.problem}; `codeloupe env set <NAME>` creates one"
        if (!access.vaultExists()) return "the secret store is empty; `codeloupe env set <NAME>` stores the first value"
        val metas = if (args.bool("all") == true) store.list() else store.visible(SecretStore.chain(args.string("workspace"), args.string("repository")))
        if (metas.isEmpty()) return "no secret applies to that workspace and repository"
        return (listOf("${metas.size} names (values are never shown):") + SecretReport.lines(metas, access.rotationDays)).joinToString("\n")
    }
}
