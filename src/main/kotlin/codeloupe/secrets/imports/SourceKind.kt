package codeloupe.secrets.imports

/** The shapes of file the scanner reads variables from. */
enum class SourceKind(val label: String) {
    DOTENV(".env file"),
    DOCKER_ENV("docker env file"),
    CLAUDE_SETTINGS("Claude settings env"),
    MCP_CONFIG("MCP server env"),
    CLAUDE_JSON("~/.claude.json env"),
}
