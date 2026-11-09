package codeloupe

import codeloupe.cli.CodeLoupeCommand
import codeloupe.cli.DaemonRefusal
import codeloupe.cli.Utf8Output
import codeloupe.platform.UserHome
import com.github.ajalt.clikt.core.main
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    UserHome.adopt()
    Utf8Output.install()
    try {
        CodeLoupeCommand(args.firstOrNull()).main(args)
    } catch (e: DaemonRefusal) {
        // A sentence the user acts on, not a stack trace: Clikt prints only its own errors that way. Anything else
        // thrown here is a fault of ours and keeps its trace.
        System.err.println(e.message)
        exitProcess(1)
    }
}
