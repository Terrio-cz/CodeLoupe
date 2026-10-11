// Runs a share of the test classes: `-PtestShard=2/3` is the second of three shares (CL-195). CI uses it to split the Windows
// tests over two runners; without the property every class runs.
//
// A class belongs to a share by its name alone, so every class runs in exactly one share and nothing needs listing when a test
// is added. The slowest classes are placed by hand, alternately, because a hash would put several of them in one share: the
// share that gets them runs minutes longer than the other. The list is only about balance; a class that is renamed or missing
// from it still runs, in the share its name hashes to. Timings: run 38143926495, Windows.

val slowTests = listOf(
    "codeloupe.overlay.OverlayTest", "codeloupe.hooks.PostEditSampleTest", "codeloupe.write.WriteFuzzTest", "codeloupe.changes.ChangesTest",
    "codeloupe.aot.AotTrainingTest", "codeloupe.write.RoundTripTest", "codeloupe.taskcode.TaskCodeTest", "codeloupe.write.RenameTest",
    "codeloupe.changes.TestSelectionTest", "codeloupe.write.WriteServiceTest", "codeloupe.taskcode.DispatchTest", "codeloupe.jobs.JobsTest",
    "codeloupe.index.ParseWorkerTest", "codeloupe.daemon.DaemonAuthTest", "codeloupe.know.KnowAnchorSearchTest",
)
// Longest first, each to the share that has the least so far (computed for two shares from the timings above).
val slowShare = listOf(0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 0)

tasks.withType<Test>().configureEach {
    val shard = (findProperty("testShard") as String?)?.split("/")?.map { it.trim().toInt() }
    if (shard != null) {
        val (index, count) = shard
        require(count >= 1 && index in 1..count) { "-PtestShard=<n>/<of> needs 1 <= n <= of, got $index/$count" }
        include { element ->
            if (element.isDirectory) return@include true
            // Nested classes (Outer$Inner.class) go with the class they sit in.
            val name = element.relativePath.pathString.removeSuffix(".class").substringBefore('$').replace('/', '.')
            val slow = slowTests.indexOf(name)
            val share = if (slow >= 0) slowShare[slow] % count else Math.floorMod(name.hashCode(), count)
            share == index - 1
        }
    }
}
