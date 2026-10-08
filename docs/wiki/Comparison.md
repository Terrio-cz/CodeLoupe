How CodeLoupe differs from the two kinds of tool an agent might use instead: a code-graph server (GitNexus) and an IDE's
built-in MCP server. Numbers marked measured come from [Benchmarks](Benchmarks).

| | CodeLoupe | GitNexus 1.6.12 | IDE-based MCP (IntelliJ IDEA's built-in server) |
|---|---|---|---|
| Needs a running IDE | No | No ([README](https://github.com/abhigyanpatwari/GitNexus#readme): CLI and MCP server, editors are clients) | Yes: the server is part of the IDE and serves "the projects opened in the IDE" ([JetBrains docs](https://www.jetbrains.com/help/idea/mcp-server.html)) |
| Languages | Kotlin and Java | 16 listed in its README: TypeScript, JavaScript, Python, Java, Kotlin, C#, Go, Rust, PHP, Ruby, Swift, C, C++, Objective-C, Dart, Zig | Those the IDE supports |
| How references are resolved | Syntax only (the Kotlin compiler's parsers for Kotlin and Java, no classpath); unsure hits are marked `candidate`, never dropped | Graph built from tree-sitter parsers; answers carry an `epistemic` field (`exact` or `lower-bound`) and list unresolved boundaries | The IDE's own semantic model |
| Index | SQLite; the default branch, built from git objects | Embedded graph database (LadybugDB), no database server; built by `gitnexus analyze` | The IDE's indexes |
| Keeping it current | No watchers: the worktree is checked when a query arrives | Re-run `analyze`, or `analyze --watch`; running MCP servers reopen a new index (README) | The IDE |
| Worktrees | One base index, an overlay per worktree; first `changes` in a new worktree took 1.1–5.9 s (measured) | README: linked worktrees share one store, a checkout with uncommitted changes gets its own incrementally updated graph (not measured) | Each opened project |
| Writes into your checkout | Nothing (measured) | `AGENTS.md`, `CLAUDE.md` section, `.claude/skills/` by default (measured; flags turn it off) | n/a |
| Tool definitions in context | 3,749 tokens, 13 tools (measured; more with a tracker configured) | 22,140 tokens, 17 tools (measured) | Not measured by the script; an earlier one-off measurement of an older IDE server: 25 tools, about 12.4k tokens ([context-audit](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/context-audit.md)) |
| Install | Bundle with its own Java runtime, 135 MB zip ([Bundle](Packaging-and-releasing#bundle)); or JDK 25 and Gradle | Node.js 22.18+ (the package's `engines`) and the npm package, 231 MB unpacked (`npm view gitnexus dist.unpackedSize`) | Part of the IDE |
| Beyond navigation | Tracker mirror, task ↔ code links, jobs in the daemon, transcript metrics | Execution flows, impact analysis, route and API maps, Cypher queries, optional embeddings, web UI (README) | Refactorings, inspections, run configurations, debugger, database tools (JetBrains docs) |
| Licence | [PolyForm Noncommercial 1.0.0](https://github.com/Terrio-cz/CodeLoupe/blob/main/LICENSE) | [PolyForm Noncommercial 1.0.0](https://github.com/abhigyanpatwari/GitNexus/blob/main/LICENSE); its README points to the maintainers for commercial licensing | Per the IDE's licence (not examined) |

## Sources

Facts about GitNexus and the JetBrains server were read from their public documentation and package metadata on
2026-10-08; "measured" means [tools/benchmark.mjs](https://github.com/Terrio-cz/CodeLoupe/blob/main/tools/benchmark.mjs).

## When another tool fits better

- the repository is not Kotlin: GitNexus (16 languages) or the IDE;
- you need the compiler's exact answer, a refactoring or inspections: an IDE-based server;
- you ask conceptual questions ("how does checkout work"), want execution flows, blast-radius analysis or API route
  maps: GitNexus has tools for them, CodeLoupe has none;
- you search text in files that are not Kotlin or `.kts`: `rg` searches every file type;
- you want only the list of changed files: `git diff --stat` is smaller than `changes` (2,247 against 2,938 tokens);
- the repository is small enough that reading the files costs little.

## Licences

Both CodeLoupe and GitNexus are source-available under the PolyForm Noncommercial licence, so neither is a free choice
for commercial use; the IDE-based server follows the IDE's licence.
