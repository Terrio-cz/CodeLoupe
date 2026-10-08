import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.license.report)
    alias(libs.plugins.cyclonedx)
    application
}

group = "dev.codeloupe"
// A release build takes the version from its tag (-PreleaseVersion=1.2.3, CL-106); it ends up in the jar, build.properties,
// the CLI, /status and the bundle name.
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0")

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.compiler)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jgit)
    implementation(libs.ktor.server.cio)
    implementation(libs.mcp.server)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.clikt)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.mcp.client)
    testImplementation(libs.ktor.client.cio)
    testRuntimeOnly(libs.junit.launcher)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // The parse-only PSI setup uses compiler internals; the opt-ins are scoped to that one adapter.
        optIn.addAll(
            "org.jetbrains.kotlin.config.CompilerConfiguration.Internals",
            "org.jetbrains.kotlin.CoreEnvironmentDeprecation",
            "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
        )
    }
}

application {
    mainClass.set("codeloupe.MainKt")
    applicationName = "codeloupe"
}

// The jar is the whole launch command (`java -jar lib/codeloupe-<v>.jar`): a short command line whatever the install
// path, and `Enable-Native-Access` in the manifest instead of a JVM flag, which a class-data archive cannot be combined with.
tasks.jar {
    manifest {
        attributes(
            "Implementation-Version" to project.version,
            "Main-Class" to application.mainClass,
            "Class-Path" to provider { configurations.runtimeClasspath.get().files.joinToString(" ") { it.name } },
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}

// Our own start scripts (gradle/start): JVM flags for a short-lived CLI plus a class-data archive under the CodeLoupe home.
tasks.startScripts {
    val jarName = tasks.jar.flatMap { it.archiveFileName }
    val unixSource = layout.projectDirectory.file("gradle/start/codeloupe")
    val windowsSource = layout.projectDirectory.file("gradle/start/codeloupe.bat")
    inputs.files(unixSource, windowsSource)
    inputs.property("jarName", jarName)
    doLast {
        // The scripts name the class-data archive after the build: a JVM never rebuilds one whose jars changed.
        val buildId = MessageDigest.getInstance("SHA-256").digest(tasks.jar.get().archiveFile.get().asFile.readBytes())
            .take(6).joinToString("") { "%02x".format(it) }
        fun script(source: RegularFile) =
            source.asFile.readText().replace("@JAR@", jarName.get()).replace("@BUILD@", buildId).replace("\r\n", "\n")
        unixScript.writeText(script(unixSource))
        windowsScript.writeText(script(windowsSource).replace("\n", "\r\n"))
    }
}

apply(from = "gradle/bundle.gradle.kts")

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("codeloupe.projectDir", projectDir.absolutePath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("codeloupe/build.properties") { expand("version" to version) }
}

licenseReport {
    // What ships to users: the runtime classpath. Test and build tooling is not distributed.
    configurations = arrayOf("runtimeClasspath")
    filters = arrayOf(com.github.jk1.license.filter.LicenseBundleNormalizer())
    renderers = arrayOf(
        com.github.jk1.license.render.JsonReportRenderer("licenses.json", false),
        com.github.jk1.license.render.CsvReportRenderer("licenses.csv"),
    )
    allowedLicensesFile = file("gradle/allowed-licenses.json")
}

// CycloneDX SBOM of what ships: the runtime classpath (`./gradlew cyclonedxDirectBom`, build/reports/cyclonedx-direct).
tasks.cyclonedxDirectBom {
    includeConfigs.set(listOf("runtimeClasspath"))
}
