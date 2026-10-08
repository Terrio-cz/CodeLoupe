package codeloupe.cli

/**
 * JVM flags of the background daemon. It answers small queries all day, so it trades peak speed for a small
 * footprint: one GC thread, a small heap, C1 only. No dynamic class-data archive: one dumped after a parse holds
 * the parser's classes and maps them into every later run (+30 MB resident) for no measurable start-up gain.
 *
 * Resident memory after a mixed query load (CL-118, `tools/rss-mix.mjs`): compact object headers shrink every object
 * of the cached rows by a quarter, a 10 MB young generation stops the eden from being touched to its last page, and
 * a tight free ratio lets the old generation give pages back after a full collection. Together ~10 MB less than
 * the defaults on TerrioImporter; the heap limit itself is unchanged.
 */
object DaemonJvm {
    fun args(): List<String> = listOf(
        "-Xms16m", "-Xmx80m", "-Xmn10m", "-Xss512k",
        "-XX:+UseSerialGC", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
        "-XX:+UseCompactObjectHeaders", "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=32m", "-XX:MaxMetaspaceSize=96m",
    )
}
