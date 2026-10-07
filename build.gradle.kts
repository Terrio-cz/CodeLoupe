plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "dev.codeloupe"
version = "0.1.0"

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
    // The CLI is short-lived: small heap, no C2, class data sharing.
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xshare:auto", "-Xss512k", "-Xmx128m",
        "--enable-native-access=ALL-UNNAMED",
    )
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

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
