package codeloupe.changes

import codeloupe.index.ModulePath
import codeloupe.query.DeclRow
import codeloupe.query.View
import codeloupe.query.usages.UsageFinder

/**
 * `changes tests=true`: the test classes that use the changed declarations (directly or through the code that uses them), as
 * the Gradle command that runs them. What the index cannot vouch for widens the run, and the answer says to what and why: a
 * build file selects the full suite, a changed resource or a declaration no test reaches selects its module.
 */
internal object TestSelection {
    fun run(set: ChangeSet, after: View, before: View?): String {
        val buildFiles = (set.files.map { it.path } + set.otherFiles).filter(::isBuildFile)
        if (buildFiles.isNotEmpty()) return fullSuite(set, buildFiles)

        val reach = TestReach(UsageFinder(after))
        val selected = LinkedHashMap<TestClass, MutableList<String>>()
        val widened = LinkedHashMap<String, MutableList<String>>()
        var declarations = 0
        val parentReach = HashMap<Pair<String, Long>, TestReach.Reach>()
        for (file in set.files) {
            val (old, new) = ChangesQuery.versions(file, after, before)
            val changes = ChangesQuery.collapse(DeclDiff.of(old, new)).map { it.first }
            for (change in changes) {
                val row = change.current.row
                if (file.status == 'D' && TestReach.isTest(row.path)) continue
                declarations++
                val name = label(row)
                val removed = change.mark == DeclChange.REMOVED
                var found = reach.of(row, removed)
                if (found.tests.isEmpty() && !TestReach.isTest(row.path)) {
                    // A new or private member is covered by the tests of the type it belongs to.
                    val parent = reach.parentOf(row, removed)
                    if (parent != null) found = parentReach.getOrPut(parent.src to parent.id) { reach.of(parent, false) }
                }
                if (found.tests.isEmpty()) {
                    val why = if (found.complete) "no test uses it" else "too common a name to follow"
                    widened.getOrPut(moduleTask(row.path)) { ArrayList() } += "$name ($why)"
                } else {
                    found.tests.forEach { selected.getOrPut(it) { ArrayList() } += name }
                }
            }
        }
        for (path in set.otherFiles) resourceTask(path)?.let { task -> widened.getOrPut(task) { ArrayList() } += "$path (not code)" }
        return render(set, declarations, mergeNested(selected), widened)
    }

    /** One entry per class: a class that was reached from a nested class is run with its nested classes whichever way it was found. */
    private fun mergeNested(selected: Map<TestClass, List<String>>): Map<TestClass, List<String>> {
        val merged = LinkedHashMap<Pair<String, String>, Pair<TestClass, MutableList<String>>>()
        for ((test, names) in selected) {
            val entry = merged.getOrPut(test.task to test.name) { test to ArrayList() }
            entry.second += names
            if (test.nested && !entry.first.nested) merged[test.task to test.name] = test to entry.second
        }
        return merged.values.associate { it.first to it.second }
    }

    private fun render(set: ChangeSet, declarations: Int, selected: Map<TestClass, List<String>>, widened: Map<String, List<String>>): String {
        if (selected.isEmpty() && widened.isEmpty()) {
            return "no test needs to run: no declaration changed in ${set.files.size} source files" +
                if (set.otherFiles.isNotEmpty()) " and ${set.otherFiles.size} other changed files are no build input" else ""
        }
        val classes = selected.keys.filter { it.task !in widened }
        val args = ArrayList<String>()
        for ((task, group) in classes.groupBy { it.task }) {
            args += task
            group.sortedBy { it.name }.forEach { args += "--tests '${it.filter}'" }
        }
        widened.keys.forEach { args += it }
        val out = ArrayList<String>()
        out += "tests for $declarations changed declarations in ${set.files.size} files: ${classes.size} test classes in ${classes.map { it.task }.distinct().size} tasks" +
            if (widened.isNotEmpty()) ", ${widened.size} whole-module runs" else ""
        out += "./gradlew " + args.joinToString(" ")
        for ((test, names) in selected.entries.filter { it.key.task !in widened }.sortedBy { it.key.name }.take(LISTED)) {
            out += "  ${test.simpleName} <- " + summary(names)
        }
        if (classes.size > LISTED) out += "  … +${classes.size - LISTED} more classes"
        for ((task, names) in widened) out += "  $task whole module: " + summary(names)
        out += "Gradle paths follow directories; uses through reflection, injection or generated code are not seen: run the module when in doubt."
        return out.joinToString("\n")
    }

    private fun fullSuite(set: ChangeSet, buildFiles: List<String>): String =
        "full suite: ./gradlew test\n  ${buildFiles.take(3).joinToString(", ")} changed; a build change can break any test, so no filter is safe" +
            "\n  (${set.files.size} source files and ${set.otherFiles.size} other files changed)"

    private fun summary(names: List<String>): String =
        names.distinct().let { it.take(NAMES).joinToString(", ") + if (it.size > NAMES) ", … +${it.size - NAMES}" else "" }

    private fun label(row: DeclRow) = if (row.container.isEmpty()) row.name else "${row.container}.${row.name}"

    // A declaration of a test source set widens to that source set's own task (integrationTest), the others to the module's `test`.
    private fun moduleTask(path: String): String = ModulePath.of(path).let { TestClass(it.module, if (TestReach.isTest(path)) it.sourceSet else "test", "", false).task }

    /** The task that runs a module's tests after a non-code file under `src` changed; null for files that no test reads. */
    private fun resourceTask(path: String): String? {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in IGNORED || path.startsWith("docs/") || path.startsWith(".github/")) return null
        val where = ModulePath.of(path)
        if (where.sourceSet.isEmpty()) return null
        return TestClass(where.module, if (where.sourceSet.contains("test", ignoreCase = true)) where.sourceSet else "test", "", false).task
    }

    private fun isBuildFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name in BUILD_FILES || name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name.endsWith(".versions.toml") ||
            path.startsWith("buildSrc/") || path.startsWith("build-logic/") || path.startsWith("gradle/") || "/buildSrc/" in path
    }

    private val BUILD_FILES = setOf("gradle.properties", "gradlew", "gradlew.bat", "libs.versions.toml", "gradle-wrapper.properties")
    private val IGNORED = setOf("md", "txt", "png", "jpg", "jpeg", "gif", "svg", "ico", "adoc", "license")
    private const val LISTED = 12
    private const val NAMES = 3
}
