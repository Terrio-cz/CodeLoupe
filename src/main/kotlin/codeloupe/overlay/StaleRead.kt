package codeloupe.overlay

import java.nio.file.Path

/** An unchanged worktree read against the base before the one that just landed, while its overlay is re-derived: [view] fits [baseFile]. */
class StaleRead(val baseCommit: String, val baseFile: Path, val view: OverlayVersion)
