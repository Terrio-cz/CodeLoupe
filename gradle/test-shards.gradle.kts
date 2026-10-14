// Runs a share of the test classes: `-PtestShard=2/3` is the second of three shares (CL-195). CI uses it to split the Windows
// tests over three runners; without the property every class runs.
//
// A class belongs to a share by its name alone, so every class runs in exactly one share and nothing needs listing when a test
// is added. The slowest classes are placed by their time, longest first, each to the share with the least so far, because a hash
// would put several of them in one share and that share would run minutes longer. The list is only about balance; a class that is
// renamed or missing from it still runs, in the share its name hashes to.

// Seconds per class on a Windows runner, the longer of runs 38480153628 and 38485361671 (classes of 10 s and more).
val slowTests = mapOf(
    "codeloupe.hooks.PostEditSampleTest" to 103,
    "codeloupe.overlay.OverlayTest" to 101,
    "codeloupe.aot.AotTrainingTest" to 91,
    "codeloupe.write.WriteFuzzTest" to 78,
    "codeloupe.write.RoundTripTest" to 70,
    "codeloupe.taskcode.TaskCodeTest" to 59,
    "codeloupe.write.RenameTest" to 51,
    "codeloupe.changes.TestSelectionTest" to 46,
    "codeloupe.changes.ChangesTest" to 43,
    "codeloupe.write.WriteServiceTest" to 39,
    "codeloupe.cli.WedgedDaemonTest" to 35,
    "codeloupe.brain.BrainAnchorsTest" to 33,
    "codeloupe.taskcode.DispatchTest" to 31,
    "codeloupe.brain.BrainRiskTest" to 29,
    "codeloupe.know.KnowStaleTest" to 29,
    "codeloupe.jobs.JobsTest" to 28,
    "codeloupe.know.KnowAnchorSearchTest" to 26,
    "codeloupe.brain.BrainServiceTest" to 25,
    "codeloupe.daemon.DaemonAuthTest" to 21,
    "codeloupe.uiapi.UiApiCommandsTest" to 20,
    "codeloupe.events.EventsTest" to 20,
    "codeloupe.write.FixturesCompileTest" to 20,
    "codeloupe.processes.ProcessStopperTest" to 16,
    "codeloupe.ingest.IngestScaleTest" to 15,
    "codeloupe.index.ParseWorkerTest" to 15,
    "codeloupe.jobs.SlotQueueTest" to 14,
    "codeloupe.query.MixedLanguageTest" to 13,
    "codeloupe.query.JavaToolsTest" to 13,
    "codeloupe.changes.TestTaskNamesTest" to 13,
    "codeloupe.workspace.WorkspaceScannerTest" to 13,
    "codeloupe.changes.JavaChangesTest" to 12,
    "codeloupe.jobs.JobTakeoverTest" to 11,
    "codeloupe.know.KnowRoutesTest" to 11,
    "codeloupe.daemon.DaemonTest" to 11,
    "codeloupe.processes.ProcessesDaemonTest" to 11,
    "codeloupe.ports.PortRoutesTest" to 10,
    "codeloupe.query.RepoMapTest" to 10,
)

/** The share (0-based) of each slow class for [count] shares. */
fun slowShares(count: Int): Map<String, Int> {
    val load = IntArray(count)
    return slowTests.entries.sortedByDescending { it.value }.associate { (name, seconds) ->
        val share = load.indices.minBy { load[it] }
        load[share] += seconds
        name to share
    }
}

tasks.withType<Test>().configureEach {
    val shard = (findProperty("testShard") as String?)?.split("/")?.map { it.trim().toInt() }
    if (shard != null) {
        val (index, count) = shard
        require(count >= 1 && index in 1..count) { "-PtestShard=<n>/<of> needs 1 <= n <= of, got $index/$count" }
        val slow = slowShares(count)
        include { element ->
            if (element.isDirectory) return@include true
            // Nested classes (Outer$Inner.class) go with the class they sit in.
            val name = element.relativePath.pathString.removeSuffix(".class").substringBefore('$').replace('/', '.')
            val share = slow[name] ?: Math.floorMod(name.hashCode(), count)
            share == index - 1
        }
    }
}
