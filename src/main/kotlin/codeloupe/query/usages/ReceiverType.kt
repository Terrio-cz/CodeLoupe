package codeloupe.query.usages

import codeloupe.query.DeclRow

/** The type of the expression before `.`, `?.` or `::`. */
internal sealed interface ReceiverType {
    /** An instance of [types] (indexed) or of a type known only by [names] (library types and supertypes). */
    data class Instance(val types: List<DeclRow>, val names: Set<String>) : ReceiverType

    /** A type used as a qualifier: `Type.member` reaches its companion, nested types, enum entries, object members. */
    data class Static(val types: List<DeclRow>) : ReceiverType

    /** `com.example.pkg.member`. */
    data class Package(val name: String) : ReceiverType

    data object Unknown : ReceiverType
}
