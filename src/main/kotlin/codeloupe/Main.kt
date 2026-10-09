package codeloupe

import codeloupe.cli.CodeLoupeCommand
import codeloupe.cli.Utf8Output
import codeloupe.platform.UserHome
import com.github.ajalt.clikt.core.main

fun main(args: Array<String>) {
    UserHome.adopt()
    Utf8Output.install()
    CodeLoupeCommand(args.firstOrNull()).main(args)
}
