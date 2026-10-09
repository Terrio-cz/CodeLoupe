package codeloupe.platform

import java.nio.file.Path

/** Command lines that start another JVM on this JVM's runtime and class path. */
object JavaProcess {
    private val java: String = ProcessHandle.current().info().command().orElse(
        Path.of(System.getProperty("java.home"), "bin", if (NativeCalls.isWindows) "java.exe" else "java").toString(),
    )

    /** The executable of the JVM that runs now. */
    val executable: String get() = java

    fun command(mainClass: String, jvmArgs: List<String>, args: List<String>): List<String> =
        listOf(java) + jvmArgs + listOf("--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), mainClass) + args
}
