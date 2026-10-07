package codeloupe

import java.util.Properties

/** Identity shared by the daemon, its clients and the build worker. */
object CodeLoupe {
    const val NAME = "codeloupe"

    /** Required on every request except `/status`: a browser cannot add it without a CORS preflight. */
    const val HEADER = "x-codeloupe"

    const val DEFAULT_PORT = 47391

    val VERSION: String = Properties().run {
        CodeLoupe::class.java.getResourceAsStream("/codeloupe/build.properties")?.use(::load)
        getProperty("version") ?: "dev"
    }
}
