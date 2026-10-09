package codeloupe.cli

/**
 * JVM flags of a CLI call, as `gradle/start/codeloupe` and `codeloupe.bat` pass them: short-lived, so a small heap,
 * no C2, serial GC. The AOT cache of the CLI (CL-150) is recorded with these flags, and a test keeps the launchers
 * and this list the same.
 */
object CliJvm {
    val args: List<String> = listOf("-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k", "-Xmx128m", "-XX:-UsePerfData", "-Xlog:disable")
}
