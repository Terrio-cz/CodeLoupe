Agent teams keep a shared-memory **brain** per workspace: facts, decisions, task notes and findings in plain files, written by one engine
(`<workspace>/brain/brain.mjs`). The daemon indexes those files and answers "what did earlier tasks find about this code" even when no
word of the question matches: a planner touching `AddressRoutes.kt` gets the findings recorded on that file, on the code that calls it, in its module and in
the tasks that changed it. The files stay the only truth; the index is a copy the daemon can throw away and rebuild.

Register the workspace once (`config.json`):

```json
{ "workspaces": { "brains": ["C:/work/team-workspace"], "repos": ["C:/work/the-code-repository"] } }
```

The workspace's `brain/workspace.json` names its code repository (`canonicalRepo`) and what each role reads (`briefs`).

## Tools

| Tool | Answers |
|---|---|
| `brain_brief` | the start of a role: state, settled findings, the task's notes, decisions on the code and the facts the role reads, as the JSON of `brain.mjs brief --json` |
| `brain_search` | whole entries for words and files, same ranking |
| `brain_risk` | where findings pile up in the files a task will touch: a score per file and module with the entries behind it (below) |

The tools are offered only when a brain workspace is registered. Upkeep is the engine's: `node brain/brain.mjs hygiene` lists near-duplicate entries (merge proposals), dangling
anchors and anchors that followed a move for a nightly review (the daemon adds the code side); nothing is rewritten.

`symbol` and `context` end with one line when entries are anchored on the declaration they answer for: `brain: 3 on Billing.total — f-3fa9… TER-5 finding: …`.

## Risk map

`brain_risk` (`POST /brain/risk`, `node brain/brain.mjs risk <files> --json`) answers "where did earlier tasks find problems in the code I am about to touch" with numbers an
orchestrator can compare. It counts the `!P<n>` lines of task notes and the findings and gotchas of the workspace facts; facts and decisions say what is true, not where
code broke. An entry weighs its severity (`P0` 10, `P1` 6, `P2` 3, `P3` 1, a fixed `x` finding 1, a gotcha 1) times `0.5^(age / half-life)` (60 days; an undated entry counts as
one half-life old). A **file** scores the entries tied to it: a file it names or a `@file:` anchor (the index resolves a bare name to the path), or a symbol the code index
finds in the file. A **module** scores the entries tied to the module plus those tied to a requested file in it, each once. `max` and `level` (`none`, `low`, `medium` from 4,
`high` from 12) are the heaviest file's; module density is reported beside it, never folded in, because a big module is always dense. Every score lists its entries (heaviest first,
`limit` of them, the rest as a count) with the whole text, the task, the weight and what tied them (`file`, `symbol`, `module`).

The engine passes the workspace's `workspace.json` `risk` block (`halfLifeDays`, `levels`, `weights`) with the request, so the weights are the workspace's, not CodeLoupe's. `as_of` / `asOf` scores
a past day and leaves out entries written later, which is how a rule is replayed over old tasks before it is switched on. A superseded entry, or one a landed automated check covers
(`coveredBy`), weighs nothing. Without the daemon the engine scores by the files the entries name; the answer says which (`source`).

## Ranking

Whole entries, never cut. In order: an entry tied to the files or symbols asked about (an anchor, or a file or symbol its text names) → one tied to a file that
calls them → one in the same module → one from a task that changed the same code (the task-to-code links of `task_code`). BM25 over the terms orders entries inside a
tier and carries the ones with no code link; an entry that predicted a finding in a later task of its own brief gets a small boost, and no entry is ever ranked
lower for having been shown. Each hit says why (`~file+caller+text`).

## Anchors

A declaration's stable id is three anchors, never a line: `@sym:<fully qualified name>` (with `(ParamType,...)` only for an overload), `@file:<path>`, `@mod:<module>`.
The engine asks the daemon, when a finding is written, to turn each `file:line` it cites into the anchors of the enclosing declaration (the declaration only where
the text names it or the line was read in the writer's own worktree, else the type the text names; file and module always). If the daemon is down the entry keeps
`file:line` and the daemon appends the anchors later. A rename or a move is followed (`@anchor <id> <new> replaces=<old>`), an anchor whose code is gone is reported as dangling.

## When the daemon is down

Nothing a team depends on waits for it: claims, locks, notes and the land gate never call it, and `brain.mjs brief` answers from the files by matching terms when the
daemon does not answer (one timeout at most). The tools exist only while the daemon runs.

The daemon appends anchor records on its own once per 15 minutes per workspace after it indexes it; `CODELOUPE_BRAIN_BACKFILL=0` turns that off.
