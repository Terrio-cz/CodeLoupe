package codeloupe.hooks.check

/** What the post-edit check came to: [Report] when the file has something to say, [Silent] with the reason when it has not. */
sealed interface PostEditOutcome {
    /** [file] has [count] findings; [text] is what the agent reads, [kind] is `syntax` or `names`. */
    class Report(val file: String, val kind: String, val count: Int, val text: String) : PostEditOutcome

    class Silent(val reason: String) : PostEditOutcome
}
