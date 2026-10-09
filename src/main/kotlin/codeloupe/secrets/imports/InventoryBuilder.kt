package codeloupe.secrets.imports

import codeloupe.config.ImportRoot
import codeloupe.secrets.SecretStore

/** Turns a scan into the report: grouped by name and suggested scope, duplicates and conflicts by salted hash, the store's state per group. */
object InventoryBuilder {
    fun build(scan: EnvScanner.Result, roots: List<ImportRoot>, store: SecretStore?): InventoryReport {
        val fingerprint = ValueFingerprint()
        val metas = runCatching { store?.list().orEmpty() }.getOrDefault(emptyList())
        val stored = metas.map { it.name to it.scope }.toSet()
        val shadowing = Shadowing(metas)
        val groups = scan.found.groupBy { it.name to it.scope.toString() }.map { (key, members) ->
            val hashes = members.map { fingerprint.of(it.value) }
            val first = members.first()
            InventoryReport.Variable(
                name = key.first,
                scope = key.second,
                sensitive = members.any { it.sensitive },
                store = storeState(store, first, members, stored),
                duplicate = hashes.groupingBy { it }.eachCount().any { it.value > 1 },
                conflict = hashes.distinct().size > 1,
                sources = members.zip(hashes).sortedBy { it.first.file.toString() }.map { (v, h) -> InventoryReport.Source(v.id, v.file.toString(), v.kind.label, v.locator, h) },
                shadows = shadowing.of(first),
            )
        }.sortedWith(compareBy({ it.name }, { it.scope }))
        val counts = InventoryReport.Counts(
            files = scan.filesRead, occurrences = scan.found.size, names = scan.found.map { it.name }.distinct().size, groups = groups.size,
            sensitive = groups.count { it.sensitive }, duplicates = groups.count { it.duplicate }, conflicts = groups.count { it.conflict },
            inStore = groups.count { it.store != "new" }, unreadable = scan.unreadable, invalidNames = scan.invalidNames, empty = scan.empty,
            references = scan.references, excludedFolders = scan.excluded.size,
        )
        return InventoryReport(roots.map { it.path.toString() }, scan.excluded.map { it.toString() }, counts, groups)
    }

    private fun storeState(store: SecretStore?, first: FoundVariable, members: List<FoundVariable>, stored: Set<Pair<String, String>>): String {
        if (listOf(first.scope, first.scope.legacy()).none { first.name to it.toString() in stored }) return "new"
        val same = runCatching { members.any { store?.holds(first.name, first.scope, it.value) == true } }.getOrDefault(false)
        return if (same) "same" else "differs"
    }
}
