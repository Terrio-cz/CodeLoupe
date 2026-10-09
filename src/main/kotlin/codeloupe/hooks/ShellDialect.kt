package codeloupe.hooks

/** The shell a command line is written for: Bash words differ from PowerShell's in escapes, grouping and aliases. */
enum class ShellDialect { POSIX, POWERSHELL }
