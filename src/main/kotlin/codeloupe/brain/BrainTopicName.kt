package codeloupe.brain

/** A topic name as the brain index prints it: segments of letters, digits, `.`, `_` and `-` joined by `/`, no `..`. */
object BrainTopicName {
    private val shape = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*")
    private const val MAX = 120

    fun valid(name: String): Boolean = name.length <= MAX && shape.matches(name)
}
