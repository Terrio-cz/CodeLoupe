package codeloupe.processes

/** What the JDK does not tell about another process on one OS: where it works, how it was started, how much memory it holds. */
internal class ProcessDetails(val cwd: String?, val commandLine: String?, val rssBytes: Long?)
