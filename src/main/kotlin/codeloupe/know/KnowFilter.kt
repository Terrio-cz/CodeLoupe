package codeloupe.know

/** Which facts a search may return: any of [scopes] (a scope without a target takes all its targets), any of [kinds]; superseded ones only on request. */
data class KnowFilter(
    val scopes: List<ScopeRef> = emptyList(),
    val kinds: List<FactKind> = emptyList(),
    val includeSuperseded: Boolean = false,
)
