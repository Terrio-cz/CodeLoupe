package codeloupe.platform

import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/** A small daemon-owned SQLite database (WAL), created with [schema] on first open. */
object Sqlite {
    fun open(file: Path, schema: List<String>): Connection {
        Files.createDirectories(file.parent)
        val config = SQLiteConfig().apply {
            setBusyTimeout(5000)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        }
        val connection = config.createConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        connection.createStatement().use { s -> schema.forEach(s::execute) }
        return connection
    }
}
