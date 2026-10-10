package codeloupe.commands

/** How the groups of a report are ranked: by the cost their results carried, by calls, or by result tokens. */
enum class CommandSort(val param: String) {
    COST("cost"), CALLS("calls"), TOKENS("tokens");

    fun ordered(groups: Collection<CommandGroup>): List<CommandGroup> = when (this) {
        COST -> groups.sortedWith(compareByDescending<CommandGroup> { it.weighted }.thenByDescending { it.resultTokens }.thenBy { it.command })
        CALLS -> groups.sortedWith(compareByDescending<CommandGroup> { it.calls }.thenByDescending { it.weighted }.thenBy { it.command })
        TOKENS -> groups.sortedWith(compareByDescending<CommandGroup> { it.resultTokens }.thenByDescending { it.weighted }.thenBy { it.command })
    }

    companion object {
        fun of(param: String): CommandSort? = entries.firstOrNull { it.param == param }
    }
}
