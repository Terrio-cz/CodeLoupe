package codeloupe.taskcode

import codeloupe.git.Git
import codeloupe.git.GitObjects

/**
 * The files that changed between two commits of the default branch, with their text before and after: what a landing can
 * have changed in a touch prediction made on the older commit. [files] is null when the landing is too large to read.
 */
class LandingDiff(val files: List<LandedText>?) {
    /** One changed file: [before] and [after] are null where the side is missing or not a blob. */
    class LandedText(val path: String, val before: String?, val after: String?)

    companion object {
        /** More changed files than this make every prediction count as touched: a merge of a long branch, a mass rename. */
        const val MAX_FILES = 200

        fun of(commonDir: String, from: String, to: String): LandingDiff {
            val changed = Git.diff(commonDir, from, to)
            if (changed.size > MAX_FILES) return LandingDiff(null)
            val shas = changed.flatMap { listOfNotNull(it.oldBlob, it.blob) }.toSet()
            val texts = HashMap<String, String>()
            // A blob that cannot be read could hold anything a prediction looks for.
            if (GitObjects.blobs(commonDir).read(shas) { sha, text -> texts[sha] = text } < shas.size) return LandingDiff(null)
            return LandingDiff(changed.map { LandedText(it.path, it.oldBlob?.let(texts::get), it.blob?.let(texts::get)) })
        }
    }
}
