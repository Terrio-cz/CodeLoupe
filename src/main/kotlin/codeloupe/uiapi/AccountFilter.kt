package codeloupe.uiapi

/** Narrows the Overview to one Claude account: its transcripts (by path prefix) and the working directories that belong to it. */
class AccountFilter(val id: String, val transcriptPrefix: String, val owns: (String) -> Boolean)
