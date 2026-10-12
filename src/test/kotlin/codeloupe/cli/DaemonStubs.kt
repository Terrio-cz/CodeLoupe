package codeloupe.cli

import java.net.InetAddress
import java.net.ServerSocket

/** Processes the daemon tests start: `stuck <port>` binds the port and never accepts (a wedged daemon), `oom` fills its heap. */
object DaemonStubs {
    const val MAIN = "codeloupe.cli.DaemonStubs"

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.first()) {
            "stuck" -> ServerSocket(args[1].toInt(), 50, InetAddress.getLoopbackAddress()).use {
                println("bound")
                Thread.sleep(Long.MAX_VALUE)
            }
            "oom" -> {
                val kept = ArrayList<ByteArray>()
                while (true) kept += ByteArray(1 shl 20)
            }
        }
    }
}
