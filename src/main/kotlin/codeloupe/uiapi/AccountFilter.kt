package codeloupe.uiapi

import codeloupe.ingest.RunScope

/** Narrows the Overview to one Claude account: its runs and the working directories that belong to it. */
class AccountFilter(val id: String, val scope: RunScope, val owns: (String) -> Boolean)
