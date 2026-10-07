package codeloupe.query.usages

/** What the parameter a lambda is passed as says about the lambda's implicit receiver. */
internal sealed interface LambdaReceiver {
    /** `() -> T`: no receiver. */
    data object None : LambdaReceiver

    /** A function outside the index: any receiver it gives is a library type, whose members are not indexed. */
    data object Library : LambdaReceiver

    /** An indexed function whose parameter type does not say (a `fun interface`, an alias, an unplaced argument). */
    data object Unknown : LambdaReceiver

    /** `R.() -> T`. */
    data class Typed(val type: TypeSpecs.TypeText) : LambdaReceiver
}
