// Self-contained bundle for the current OS: the install layout (bin/, lib/) plus a jlink runtime in runtime/, so the
// machine needs no JDK. jlink output is specific to the OS it runs on, so each OS builds its own (CI does).
// The bin/ launchers use runtime/ when it is there. The Electron installer takes this directory as is.
//
//   ./gradlew bundle   ->  build/distributions/codeloupe-<version>-<os>-<arch>.zip

val bundleOs = System.getProperty("os.name").lowercase().let {
    when {
        it.startsWith("windows") -> "windows"
        it.startsWith("mac") -> "macos"
        else -> "linux"
    }
}
val bundleArch = System.getProperty("os.arch").lowercase().let { if (it == "amd64" || it == "x86_64") "x64" else if (it == "aarch64") "arm64" else it }
val bundleName = "codeloupe-${project.version}-$bundleOs-$bundleArch"

// What the jars use, found by jdeps, plus what only shows up at run time: TLS to trackers (EC curves), zip file
// systems, the management beans behind the daemon's /status, java.util.logging.
val extraModules = listOf("java.logging", "jdk.crypto.ec", "jdk.zipfs", "jdk.management")

// jdeps lists what the classes of the jars mention, and the Kotlin compiler mentions javac without running it to parse a file:
// jdk.compiler is 3 MB of classes plus the 10 MB `ct.sym` that comes with it. (java.desktop looks as unused but is not: without
// it the parser worker returns no facts.) tools/bundle-smoke.mjs parses Kotlin and Java files with the bundle's runtime.
val skippedModules = setOf("jdk.compiler")

// Files jlink leaves in a runtime that nothing here uses: the class-data archive for heaps over 32 GB (the daemon runs with
// 80 MB, the worker with 512 MB, so compressed oops always apply and `classes_nocoops.jsa` is never mapped), and the import
// library for linking against the JVM (Windows).
val unusedRuntimeFiles = listOf("classes_nocoops.jsa", "jvm.lib")

// Script plugins have no generated accessors for the java and application plugins.
val javaExtension = extensions.getByType<JavaPluginExtension>()
val toolchainHome = extensions.getByType<JavaToolchainService>().launcherFor(javaExtension.toolchain).map { it.metadata.installationPath.asFile }
val installDist = tasks.named("installDist")

val bundleRuntime = tasks.register("bundleRuntime") {
    description = "Builds the minimal jlink runtime for the daemon and CLI."
    group = "distribution"
    val libDir = layout.buildDirectory.dir("install/codeloupe/lib")
    val runtimeDir = layout.buildDirectory.dir("bundle/runtime")
    dependsOn(installDist)
    inputs.dir(libDir)
    inputs.property("extraModules", extraModules)
    inputs.property("skippedModules", skippedModules)
    inputs.property("unusedRuntimeFiles", unusedRuntimeFiles)
    inputs.property("jdk", toolchainHome.map { it.absolutePath })
    outputs.dir(runtimeDir)
    doLast {
        val jdk = toolchainHome.get()
        val exe = if (bundleOs == "windows") ".exe" else ""
        fun run(vararg command: String, capture: Boolean = false): String {
            val process = ProcessBuilder(*command).redirectErrorStream(false)
                .redirectError(ProcessBuilder.Redirect.INHERIT).also { if (!capture) it.redirectOutput(ProcessBuilder.Redirect.INHERIT) }.start()
            val out = if (capture) process.inputStream.readAllBytes().toString(Charsets.UTF_8) else ""
            check(process.waitFor() == 0) { "${command.first()} failed" }
            return out
        }
        val jars = libDir.get().asFile.listFiles { f -> f.extension == "jar" }!!.map { it.absolutePath }.sorted()
        val found = run(
            jdk.resolve("bin/jdeps$exe").absolutePath, "--multi-release", javaExtension.toolchain.languageVersion.get().toString(),
            "--ignore-missing-deps", "--print-module-deps", *jars.toTypedArray(), capture = true,
        ).trim().split(',').filter { it.isNotBlank() }
        val modules = (found + extraModules).filter { it !in skippedModules }.toSortedSet().joinToString(",")
        logger.lifecycle("jlink modules: $modules")
        val out = runtimeDir.get().asFile
        out.deleteRecursively()
        run(
            jdk.resolve("bin/jlink$exe").absolutePath, "--add-modules", modules, "--output", out.absolutePath,
            "--strip-debug", "--no-header-files", "--no-man-pages", "--compress", "zip-6", "--generate-cds-archive",
        )
        out.walkTopDown().filter { it.isFile && it.name in unusedRuntimeFiles }.forEach { check(it.delete()) { "cannot delete $it" } }
    }
}

val bundleDir = tasks.register<Sync>("bundleDir") {
    description = "Lays out the bundle: bin/, lib/ and runtime/."
    group = "distribution"
    into(layout.buildDirectory.dir("bundle/$bundleName"))
    from(installDist)
    from(bundleRuntime) { into("runtime") }
}

tasks.register<Zip>("bundle") {
    description = "Zips the bundle for this OS."
    group = "distribution"
    archiveFileName.set("$bundleName.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(bundleDir) { into(bundleName) }
}
