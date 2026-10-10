A small store of facts agents learned (decisions, patterns, blockers, environment notes), kept by the daemon in its own
database `know.db`. A fact is one line with a scope, a kind, a source and, optionally, the code it holds for. Search
returns at most a handful of compact lines with ids; a fact whose anchored code changed since it was verified comes back
marked **STALE**. It is written for the files agent teams keep by hand (`facts.md` with one fact per line) and replaces
their weak points: no search, repeated lines, and notes about files that no longer exist.

Nothing is added to the MCP tool list: the store is used through the CLI and the daemon's HTTP routes. An MCP tool for it is
a later step, behind an option.

## A fact

| Field | Meaning |
|---|---|
| `id` | `k17`, stable; the number alone works too |
| scope | what it is about: `repo`, `area:docker`, `file:src/Foo.kt`, `symbol:Foo.bar`, `task:TER-12` |
| kind | `decision`, `pattern`, `blocker`, `environment`, `other` |
| text | one line (white space folded, at most 2000 characters) |
| source | the task and the run that wrote it |
| anchors | the code it holds for (below) |
| verified at | the commit of the checkout when the anchors were last fingerprinted |
| `supersedes`, `related` | ids of the facts it replaces (those leave the search) and of related ones |
| status | `active`, `stale`, `superseded`, with the reason for a stale one |

## Writing: one fact once

```bash
codeloupe know put "retries back off exponentially, capped at a minute" --scope area:webhooks --kind decision --task CL-86
codeloupe know put "Foo.parse accepts only UTC" --scope symbol:src/Foo.kt#Foo.parse --kind pattern
codeloupe know put "the new rule" --scope area:webhooks --supersedes k41
```

A fact of the **same scope** whose words are near-identical to an older one (stems with Jaccard similarity of at least
0.85, and the same numbers) **updates the older fact** instead of adding a line: the text and source are replaced, links
and anchors are added up, the id and creation time stay. `TER-167` and `TER-168` are two facts however alike the rest is.

## Anchors and staleness

An anchor ties a fact to a file, to a declaration, or to both: `--anchor src/Foo.kt`, `--anchor src/Foo.kt#Foo.parse`,
`--anchor Foo.parse` (the text is a path when it has a directory or a known extension). A `file:` or `symbol:` scope anchors
itself. `--root` names the checkout the paths belong to (default: the current directory); an anchor may name another one.

When a fact with anchors is written or verified, the daemon **fingerprints** them through the code index: a digest of the
signature and one of the code of the declaration (of all declarations of the file for a file anchor), and the commit of the
checkout. Whenever a fact is shown, its anchors are read again, with the worktree's overlay, so an edit that is not committed
yet counts:

| What happened | Flag |
|---|---|
| the signature of the declaration changed | `STALE: signature changed: path#Foo.parse` |
| the code changed, the signature did not | `STALE: body changed: …` |
| the declaration or the file is gone | `STALE: declaration gone: …` / `STALE: file gone: …` |

A fact that was never fingerprinted (an import) is only checked for existence of its target. A file the index does not
hold (Markdown, YAML, SQL) is looked up on disk, in a folder without a git repository too. A checkout that cannot be read
leaves the status as it was. If the code returns to the verified state the flag goes away; `know verify <id>` records
that the fact holds now (a new fingerprint and commit), and refuses with the reason when the anchor is gone. A type's
digest covers its members, so a fact anchored to a class goes stale on any change in it; anchor the member instead.

```bash
codeloupe know stale          # reads every anchored fact, lists the stale ones
codeloupe know verify k17
codeloupe know show k17       # text, source, anchors and whether they are fingerprinted
```

## Search

```bash
codeloupe know search "how are retries backed off" --scope area:webhooks --kind decision --limit 5
k41 decision area:webhooks  retries use exponential backoff capped at one minute (CL-86)
k77 pattern symbol:src/Foo.kt#Foo.parse STALE: body changed: src/Foo.kt#Foo.parse  Foo.parse accepts only UTC (CL-90)
```

Full-text search (SQLite FTS5, BM25 ranking) over the words of the text, scope, kind and task, with the stemming the code
search uses (`retries`, `retried` and `retry` are one word; a longer word also matches as a prefix). `--scope` takes a
comma-separated list, a bare `area` stands for every area; `--all` also returns superseded facts. At most `--limit` lines
(default 5, at most 30), each at most about 300 characters; the line is `id kind scope [STALE: reason]  text (task)`.

## Importing brain notation files

```bash
codeloupe know import brain/facts/docker.md --root /work/importer --root /work/workspace
```

Reads files in the one-fact-per-line notation (`+` fact, `!` blocker, `~` decision, `=` evidence, `?` unknown, `>` action,
`->` handoff, `x`; `@` context and `%` state lines belong to one task's moment and are skipped; an indented line
continues the fact above). The scope is `area:<file name>` unless `--scope` is given; `!` gives kind blocker, `~` decision,
`=` environment, `+` environment (pattern in a file named for patterns, or `--plus-kind`), everything else other. A trailing
`<!-- 2026-09-23 13:16:09 run-name -->` gives the creation time and the run, the first task id (`TER-167`) the source, and
every cited path (`app/src/Foo.kt:88`) a file anchor, looked for in the `--root` checkouts in order. A line that says
`SUPERSEDED` is stored as superseded. Repeats are merged by the same rule as a write, and the command reports lines,
facts, skipped lines, merged repeats and the facts that cite a file that is gone. `--dry-run` only counts.

## HTTP

All behind the daemon token (`x-codeloupe-token`), like every route that is not a read-only code query.

| Route | |
|---|---|
| `GET /know/search?q=&scope=&kind=&limit=&all=1` | `{"hits": [{"fact": …, "line": "…"}]}` |
| `POST /know` | one fact `{"scope", "text", "kind", "task", "run", "root", "anchors": [{"path", "symbol", "root"}], "supersedes", "related", "verify"}` or `{"facts": [...]}`; answers `{"action": "added" or "updated", "fact": …, "replaced": n}` |
| `GET /know/{id}` | one fact |
| `GET /know/stale` | `{"checked", "unreachable", "stale": [hits]}` after reading every anchored fact |
| `POST /know/{id}/verify` | fingerprints the anchors now; 409 with the reason when one is gone |

## Measured

The numbers, and what they mean for the cost of agents, are in `docs/plan.md` (section 8.10).
