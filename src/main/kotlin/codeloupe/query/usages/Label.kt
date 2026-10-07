package codeloupe.query.usages

/** How sure a hit is: [EXACT] resolves to the target only, [CANDIDATE] may, [OTHER] resolves elsewhere. */
enum class Label(val mark: String) { EXACT("="), CANDIDATE("?"), OTHER("-") }
