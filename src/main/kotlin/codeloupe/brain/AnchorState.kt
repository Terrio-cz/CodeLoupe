package codeloupe.brain

/** What the code says about an anchor now: still there, moved or renamed to [Moved.to], or gone. */
sealed interface AnchorState {
    data object Found : AnchorState
    data class Moved(val to: String) : AnchorState
    data object Gone : AnchorState
}
