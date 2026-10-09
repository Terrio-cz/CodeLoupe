package codeloupe

import java.util.Properties

/** Identity shared by the daemon, its clients and the build worker. */
object CodeLoupe {
    const val NAME = "codeloupe"

    /** Required on every request except `/status`: a browser cannot add it without a CORS preflight. */
    const val HEADER = "x-codeloupe"

    /** The secret in `<home>/daemon.token`: what the daemon's own clients present on every route that acts for the user. */
    const val TOKEN_HEADER = "x-codeloupe-token"

    /** A client's challenge on `GET /status` and the daemon's answer: proof that it holds the token, without sending it. */
    const val NONCE_HEADER = "x-codeloupe-nonce"
    const val PROOF_HEADER = "x-codeloupe-proof"

    const val DEFAULT_PORT = 47391

    val VERSION: String = Properties().run {
        CodeLoupe::class.java.getResourceAsStream("/codeloupe/build.properties")?.use(::load)
        getProperty("version") ?: "dev"
    }
}
