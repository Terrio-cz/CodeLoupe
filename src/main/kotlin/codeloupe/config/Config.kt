package codeloupe.config

import java.nio.file.Path

data class Config(
    val home: Path,
    val port: Int,
    val queryTimeoutMs: Long,
    val buildTimeoutMs: Long,
    val buildHeapMb: Int,
    val defaultRoot: String?,
)
