package codeloupe.accounts

import java.nio.file.Files
import java.nio.file.Path

/** Builds the `.claude.json` of a Claude login from made-up values, never from a real file. */
object LoginFiles {
    const val EMAIL = "someone@example.invalid"

    fun write(file: Path, uuid: String?): Path {
        Files.createDirectories(file.parent)
        val account = if (uuid == null) "" else """"oauthAccount":{"accountUuid":"$uuid","emailAddress":"$EMAIL","organizationUuid":"org-1"},"""
        Files.writeString(file, """{$account"numStartups":3}""")
        return file
    }
}
