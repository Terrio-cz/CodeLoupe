package codeloupe.processes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a process is to a build: a daemon that outlives its build, a client that runs one, or something else. */
@Serializable
enum class ProcessKind(val buildTool: Boolean) {
    @SerialName("gradle-daemon") GRADLE_DAEMON(true),
    @SerialName("gradle-worker") GRADLE_WORKER(true),
    @SerialName("kotlin-daemon") KOTLIN_DAEMON(true),

    /** A `gradlew` / `gradle` invocation: a build is running while it lives. */
    @SerialName("gradle-client") GRADLE_CLIENT(false),
    @SerialName("other") OTHER(false),
    ;

    companion object {
        /** The kind a command line says; only the main classes and jars the build tools are started with count. */
        fun of(commandLine: String): ProcessKind = when {
            "org.gradle.launcher.daemon.bootstrap.GradleDaemon" in commandLine -> GRADLE_DAEMON
            "KotlinCompileDaemon" in commandLine -> KOTLIN_DAEMON
            "GradleWorkerMain" in commandLine -> GRADLE_WORKER
            "org.gradle.launcher.GradleMain" in commandLine || "org.gradle.wrapper.GradleWrapperMain" in commandLine || "gradle-wrapper.jar" in commandLine -> GRADLE_CLIENT
            else -> OTHER
        }
    }
}
