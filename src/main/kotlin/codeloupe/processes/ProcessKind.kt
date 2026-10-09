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
        fun of(commandLine: String): ProcessKind {
            // An argument that ends in the name, not any text that holds it: a prompt or a file name that mentions a build tool is not one.
            val arguments = commandLine.split(' ', '\t').map { it.trim('"', '\'') }
            fun runs(vararg names: String) = arguments.any { argument -> names.any { argument.endsWith(it) } }
            return when {
                runs("org.gradle.launcher.daemon.bootstrap.GradleDaemon") -> GRADLE_DAEMON
                runs("org.jetbrains.kotlin.daemon.KotlinCompileDaemon") -> KOTLIN_DAEMON
                runs("org.gradle.process.internal.worker.GradleWorkerMain") -> GRADLE_WORKER
                runs("org.gradle.launcher.GradleMain", "org.gradle.wrapper.GradleWrapperMain", "gradle-wrapper.jar") -> GRADLE_CLIENT
                else -> OTHER
            }
        }
    }
}
