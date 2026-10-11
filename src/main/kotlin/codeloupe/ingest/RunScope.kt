package codeloupe.ingest

import java.sql.PreparedStatement

/**
 * Which runs one Claude account owns: those whose transcript lies under its `projects` folder ([underPrefix]), or those of the sessions
 * stored with its id ([ofAccount], CL-231). The second is the only right one when accounts share a config directory, since then the
 * folder proves nothing.
 */
class RunScope private constructor(private val template: String, private val argument: String) {
    /** The SQL condition on the runs table known as [runs] here; it takes one parameter, bound with [bind]. */
    fun condition(runs: String): String = template.replace("{r}", runs)

    fun bind(s: PreparedStatement, index: Int) = s.setString(index, argument)

    companion object {
        /** Runs whose transcript path starts with [prefix] (as [AccountUsage.prefix] makes it). */
        fun underPrefix(prefix: String): RunScope =
            RunScope("{r}.path LIKE ? ESCAPE '\\'", prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")

        fun ofAccount(id: String): RunScope = RunScope("{r}.path IN (SELECT path FROM files WHERE account = ?)", id)
    }
}
