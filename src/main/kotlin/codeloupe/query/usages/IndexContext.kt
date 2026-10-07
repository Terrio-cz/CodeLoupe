package codeloupe.query.usages

import codeloupe.query.View

/** The lookups over one view that every navigation query shares. */
internal class IndexContext(view: View) {
    val cache = IndexCache(view)
    val visibility = Visibility(cache)
    val types = Types(cache, visibility)
    val lookup = MemberLookup(cache, types, visibility)
    val overrides = Overrides(cache, types)
    val arguments = Arguments(cache)
}
