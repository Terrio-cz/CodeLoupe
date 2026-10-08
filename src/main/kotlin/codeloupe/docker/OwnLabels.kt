package codeloupe.docker

/** `--label` flags for a docker command line, and the guard that keeps a caller from writing the ownership labels itself. */
object OwnLabels {
    fun flags(ownership: Ownership): List<String> = ownership.labels().flatMap { (key, value) -> listOf("--label", "$key=$value") }

    /** Throws when [args] set a `codeloupe.*` label: those say who owns a resource and come from the workspace only. */
    fun rejectOwn(args: List<String>) {
        val labels = args.windowed(2).filter { (flag, _) -> flag == "--label" || flag == "-l" }.map { it[1] } +
            args.filter { it.startsWith("--label=") }.map { it.removePrefix("--label=") }
        labels.firstOrNull { it.startsWith("codeloupe.") }?.let { throw IllegalArgumentException("$it: codeloupe.* labels are set by CodeLoupe from the workspace and cannot be given") }
    }
}
