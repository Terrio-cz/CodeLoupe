Several worktrees of one repository, each with its own uncommitted edits, share one index. This page explains how, and why
nothing has to be refreshed by hand.

## Base and overlay

- **Base**: the index of the default branch's commit, built from git objects (so it does not depend on the state of any
  checkout). Every worktree of the repository reads it. The default branch is `origin/HEAD`, else `origin/main`,
  `origin/master`, `main`, `master`; `.codeloupe.json` `{ "baseBranch": "origin/master" }` in the main worktree overrides it.
- **Overlay**: per worktree, the facts of the files that differ from the base: changed, new and deleted ones (a deleted
  file is a tombstone). A query reads the base with the worktree's overlay on top. The overlay is keyed by the
  worktree's path, not its branch name, so it works for any workflow and for the main checkout too.
- A worktree's overlay is correct whatever the age of the base; a stale base only makes the overlay larger.

## When the worktree is checked

There is no file watcher. A worktree is checked **when a query arrives**:

1. The first query for a new worktree (or a new base) asks git: the diff against the base commit, untracked files,
   ignored directories.
2. Later queries list the worktree's directories (modification time and size from the listing, no git process) and parse
   again only the files whose stamp changed; content is compared with the base, so a file touched without a change, or
   with only line endings or a byte order mark different, counts as unchanged.
3. A check younger than `overlayCheckMs` (1 s) is reused, so a burst of queries pays for one listing; between an edit and
   the next question an agent always waits for the model's turn. A query reads while a check runs; if the check changes
   nothing the answer stands.
4. The state of the last check lives beside the overlay, so the first query after a daemon restart only walks the
   directories and needs no git. It is trusted only when it matches the overlay file exactly and the stamps of `HEAD`,
   the git index and `info/exclude` are the same as at the last check; a changed `.gitignore` forces git again.

A repository with more than 40 000 indexed files (`largeWorktreeFiles`) is checked through git alone (changed and
untracked files; the stat cache and, if you enabled them, `core.fsmonitor` and `core.untrackedCache` make that fast).
Edits made by the `edit` tool show at once: the overlay is refreshed after the write.

## When the default branch moves

The base is synced lazily, the first time a query arrives after the default branch moved: the previous base plus the
files that changed (changed blobs only). Small syncs are done while the query waits; larger ones run in a child JVM and
queries are answered from the old base meanwhile. The previous base stays until the next switch, and an overlay built
after a switch copies the facts of unchanged files from it instead of parsing them again.

## Housekeeping

The overlay of a worktree that no longer exists (gone from `git worktree list` or its directory missing) is deleted at
the first query for the repository after the daemon starts and after every switch of the base. No hooks into your
workflow are needed. Overlays and bases are removed and rebuilt freely: they are a cache of what git holds.

## What it costs

| Measured (public repositories, Windows 11) | Value |
|---|---|
| First `changes` in a new worktree | 1.1-5.9 s |
| Warm query | 10-11 ms |
| `changes` of a branch, warm | about 750 ms |
| Files written into your checkout | 0 |

`changes` compares the worktree with the merge-base with the default branch (committed and uncommitted work) and lists the
declarations that were added, changed (body or signature) or removed, each with its callers and tests. The registry of
worktrees, their branches, tasks and Docker leftovers is in [Workspaces and Docker cleanup](Workspaces-and-Docker-cleanup).
