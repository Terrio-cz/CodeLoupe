package codeloupe.secrets

import codeloupe.platform.JavaProcess

/** A child process for `env run` tests: prints what it received in PROBE_SECRET (it would, a real program may too) and exits as told. */
object EnvProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val value = System.getenv("PROBE_SECRET")
        println("got=$value")
        println("length=${value?.length}")
        System.err.println("on stderr: $value")
        System.exit(args.firstOrNull()?.toInt() ?: 0)
    }

    fun command(exit: Int = 0): List<String> =
        JavaProcess.command(EnvProbe::class.java.name, listOf("-Xmx32m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC"), listOf(exit.toString()))
}
