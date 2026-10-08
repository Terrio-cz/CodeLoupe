package codeloupe.compress

/** Terminal colour and cursor codes, which only cost tokens. */
object Ansi {
    private val CODES = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    fun strip(text: String): String = CODES.replace(text, "")
}
