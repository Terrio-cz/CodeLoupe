package codeloupe.metrics

/** What a role's runs cost in money at the table's prices; runs whose model the table does not know are counted, not priced. */
data class RoleMoney(val runs: Int, val priced: Double, val unpricedRuns: Int, val unpricedModels: Set<String>)
