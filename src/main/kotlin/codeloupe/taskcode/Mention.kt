package codeloupe.taskcode

/** Something an issue's text names that may be code: what it is, the text as written, and where it was said. */
data class Mention(val kind: Kind, val text: String, val where: String) {
    enum class Kind {
        /** A repository path, possibly a suffix (`…/mail/MailSender.kt`, `SourceRoutes.kt:43-50`). */
        PATH,

        /** A declaration in code spans: `Type`, `Type.member`, `member()`, `pkg.Type`. */
        SYMBOL,

        /** A CamelCase word in prose. */
        WORD,

        /** A string the code holds: an API route (`/v1/sources/{id}`), a table (`public_api.usage_events`). */
        LITERAL,

        /** A module path (`importers/ruian`). */
        MODULE,
    }

    /** `\`text\` in Scope`, `word in criterion 2`. */
    val evidence: String get() = when (kind) {
        Kind.WORD -> "$text in $where"
        Kind.LITERAL -> "`$text` in $where"
        else -> "`$text` in $where"
    }
}
