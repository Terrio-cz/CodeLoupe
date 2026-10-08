package codeloupe.processes

/** The report as plain text: the memory per workspace, then the processes under it. */
object ProcessRender {
    fun text(report: ProcessReport, workspace: String? = null): String = buildString {
        val shown = report.workspaces.filter { workspace == null || it.workspace.equals(workspace, ignoreCase = true) }
        if (shown.isEmpty()) append("no process works in a workspace\n")
        for (ram in shown) {
            append(ram.repo).append('/').append(ram.workspace).append(" (").append(ram.state.name.lowercase()).append(")  ")
            append(ram.processes).append(" processes, ").append(ram.rssMb).append(" MB, ").append(ram.buildRssMb).append(" MB of them build tools\n")
            for (p in report.processes.filter { it.repo == ram.repo && it.workspace == ram.workspace }) {
                append("  ").append(p.kind.name.lowercase().replace('_', '-').padEnd(14)).append("pid ").append(p.pid.toString().padEnd(7)).append(p.rssMb.toString().padStart(6)).append(" MB  ")
                append(p.name).append("  ").append(p.commandLine.take(LINE)).append('\n')
            }
        }
        for (problem in report.problems) append("! ").append(problem).append('\n')
    }

    private const val LINE = 100
}
