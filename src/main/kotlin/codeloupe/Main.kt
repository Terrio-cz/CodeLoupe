package codeloupe

import codeloupe.cli.CodeLoupeCommand
import codeloupe.cli.Utf8Output
import com.github.ajalt.clikt.core.main

fun main(args: Array<String>) {
    Utf8Output.install()
    CodeLoupeCommand().main(args)
}
