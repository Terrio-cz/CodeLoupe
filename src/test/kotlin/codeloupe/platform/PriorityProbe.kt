package codeloupe.platform

/** A JVM for [ProcessPriorityTest]: lowers its own priority, says its pid, and stays until its input is closed. */
object PriorityProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val early = Thread({ Thread.sleep(60_000) }, "made-before").apply { isDaemon = true; start() }
        ProcessPriority.lower()
        val late = Thread({ Thread.sleep(60_000) }, "made-after").apply { isDaemon = true; start() }
        println(ProcessHandle.current().pid())
        System.out.flush()
        System.`in`.read()
        early.interrupt()
        late.interrupt()
    }
}
