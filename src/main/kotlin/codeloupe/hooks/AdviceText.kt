package codeloupe.hooks

/** What the model reads when a hook points it at a CodeLoupe call; short, because it stays in the context. */
object AdviceText {
    fun advise(advice: Advice): String = "CodeLoupe answers this from its index: ${advice.call()} (shell: ${advice.cli()})." + note(advice)

    fun deny(advice: Advice): String =
        "Not run. CodeLoupe answers this from its index: ${advice.call()} (shell: ${advice.cli()})." + note(advice) + " Run the same command again if that does not help."

    private fun note(advice: Advice) = if (advice.note.isEmpty()) "" else " ${advice.note.replaceFirstChar { it.uppercase() }}."
}
