package codeloupe.know

/**
 * Which facts a search may return: any of [scopes] (a scope without a target takes all its targets), any of [kinds]; superseded ones only on request.
 * With an [anchor] the store only narrows down (see [AnchorMatch.prefilter]); the caller keeps the facts that [AnchorMatch.matches] says are anchored there.
 */
data class KnowFilter(
    val scopes: List<ScopeRef> = emptyList(),
    val kinds: List<FactKind> = emptyList(),
    val includeSuperseded: Boolean = false,
    val anchor: AnchorRef? = null,
)
