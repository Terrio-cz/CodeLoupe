package codeloupe.uiapi

import codeloupe.secrets.SecretAccess
import codeloupe.secrets.SecretAge
import codeloupe.secrets.SecretAudit
import java.time.Instant

/** The Environment screen's data, from the vault's metadata and the audit; it never decrypts anything. */
internal class EnvironmentViews(private val access: SecretAccess, private val rotationDays: Int, private val now: () -> Instant = Instant::now) {
    fun keys(): EnvironmentView {
        val store = access.store ?: return EnvironmentView(emptyList(), storeReady = false, rotationDays = rotationDays)
        val metas = runCatching { store.list() }.getOrElse { return EnvironmentView(emptyList(), storeReady = false, rotationDays = rotationDays) }
        val readers = access.audit.consumers()
        val keys = metas.map { meta ->
            val seen = readers[SecretAudit.key(meta.name, meta.scope)].orEmpty()
            val (scope, ref) = split(meta.scope)
            EnvironmentView.Key(
                name = meta.name, scope = scope, scopeRef = ref,
                source = if (meta.source == "manual" || meta.source == "app") "store" else "file", sourceRef = meta.source.takeIf { it != "manual" && it != "app" },
                consumers = seen.map { it.consumer }.ifEmpty { meta.usedBy }, reads = seen.sumOf { it.reads },
                lastUsedAt = meta.lastUsed, createdAt = meta.created, updatedAt = meta.rotated ?: meta.created,
                ageDays = SecretAge.days(meta, now()), rotationDue = SecretAge.due(meta, rotationDays, now()),
            )
        }
        return EnvironmentView(keys, storeReady = true, rotationDays = rotationDays)
    }

    fun audit(name: String?, scope: String?, limit: Int): EnvironmentAuditView =
        EnvironmentAuditView(access.audit.recent(limit, name, scope).map { e ->
            val (kind, ref) = split(e.scope)
            EnvironmentAuditView.Event(e.at, e.name, kind, ref, e.action.name.lowercase(), e.consumer)
        })

    private fun split(scope: String): Pair<String, String?> = scope.substringBefore(':').let { kind -> if (kind == scope) "global" to null else kind to scope.substringAfter(':') }
}
