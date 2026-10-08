Every working directory of a repository (a worktree) is a workspace. These commands and screens show what each holds,
and clean up what a finished task leaves behind: Docker containers, images, volumes and networks, build daemons, ports and
orphan directories. Nothing is removed unless it carries the workspace's label or a rule you wrote, and unowned resources
are only reported.

## Workspaces

`codeloupe workspaces [--repo <path>] [--state orphan] [--size] [--ram] [--json]` and `GET /workspaces?repo=&size=1&ram=1` (JSON, for the
app) list every worktree of the configured repositories: role, branch, task id (from the branch name, else the directory
name, by the repository's task pattern), commits ahead of the default branch, the task's state from the tracker mirror,
last activity (newer of the HEAD commit and the last git operation in the worktree) and, with `size`, disk size and, with `ram`, the
working set of the processes that work in the directory and how many they are.
State: `active`; `landed` (everything is on the default branch and the task is resolved or has commits there);
`abandoned` (unmerged work, idle for more than `abandonedDays`); `orphan` (a directory under a worktree root git has no
worktree for, or a worktree whose directory is gone). Nothing is removed: orphans are only reported. Git is read in-process,
no `git` process runs. The first call in a repository waits for the scan of its history (`task_code` shares it).

```json
{ "workspaces": { "abandonedDays": 14, "repos": [ { "path": "C:/ws/acme", "roots": ["C:/ws/acme-worktrees"] } ] } }
```

Repositories also come from a tracker's `repos` and from the repositories the daemon has served; `<repo name>-worktrees`
beside a repository is always a root.

The read-only routes that need the registry (`/workspaces` without `repo` and `size`, `/resources`, `/processes`, the dry run `GET /reconcile`,
`/ports`) share one scan for `workspaces.recentScanMs` (default 2000, 0 = every read scans for itself), so the desktop app's Workspaces
screen, which asks four of them at once, costs one scan. What decides something never uses it: `POST /reconcile/run`, the reconcile
scheduler and a release read the registry and Docker afresh, and a release or a run that changed something drops the shared scan.

## Docker resources

Every container, image, volume and network that is made through CodeLoupe carries three labels naming its owner:
`codeloupe.repo` (the repository's main worktree), `codeloupe.workspace` (the worktree directory) and `codeloupe.task`
(the workspace's task id, empty for one without). The workspace is the one of the directory you run in (`--dir` names
another), found through the registry above.

```
codeloupe ws up [-f compose.yaml] [-p project] [--profile x] [--env-file f] [--project-directory d] [-- up-args]   # default: -d
codeloupe ws run [--dir d] <docker run arguments>
codeloupe ws build [--dir d] <docker build arguments>
codeloupe ws volume create <name>
codeloupe ws resources [--class owned|adopted|unowned] [--json]     # GET /resources
```

`ws volume create` and the inventory use the Docker Engine API (named pipe `\\.\pipe\dockerDesktopLinuxEngine` /
`docker_engine`, or a unix socket; `DOCKER_HOST` with `npipe://` or `unix://` is honoured): no `docker` process, no
output parsing. Compose, build and the full `docker run` command line are client side, so those three call `docker`
with the labels added and check the result through the API: `ws up` reads `docker compose config --format json` and adds
the labels through a generated override file to every service (containers), to `build` (images the project builds),
and to the project's own volumes and networks (not to `external` ones); `ws build` passes `--label` and verifies the
image; `ws run` passes `--label` and first creates the named volumes it mounts, labelled (Docker would create them
without). A `codeloupe.*` label given by the caller is refused, a volume that exists and is not the workspace's is
never relabelled. Exit code 3: something the command made came out without the labels. Images a project only pulls
are not created by CodeLoupe and carry no labels.

`ws resources` lists what exists, by owner: **owned** (the labels), **adopted** (an adoption rule of the config maps its
name to a workspace, for resources made before the labels existed) and **unowned**, which is only reported — nothing
in CodeLoupe changes a resource it does not own, and adoption itself changes nothing in Docker: it is this mapping.
Owned and adopted rows show the workspace's state in the registry (`not in registry` when its worktree is gone).

```json
{ "workspaces": { "adoption": [
  { "repo": "acme-app", "match": "^acme-abc-(\\d+)(?:[-_].*)?$", "workspace": "ABC-$1", "task": "ABC-$1" },
  { "repo": "acme-app", "match": "^(?:acme-)?app:abc-(\\d+)(?:-.*)?$", "kinds": ["image"], "workspace": "ABC-$1", "task": "ABC-$1" }
] } }
```

`match` is a case-insensitive regular expression tried against each name of the resource (container name, image
`repo:tag`, volume or network name) and against the compose project it belongs to; `$1`… stand for its groups. Rules are
tried in order, the first one wins, labels beat rules. Containers, volumes and networks are also matched by their compose
project. Images are not, by default: compose labels an image with the project that built it, but images get re-tagged and
shared between tasks (`aot`, `jdk25`), so the project alone does not make one a task's leftover. A rule with
`"matchProject": true` (and `"kinds": ["image"]`) adopts the untagged and re-tagged images a stack built; the `via`
column says which name matched.

## Cleanup of released workspaces (reconciler)

`codeloupe ws reconcile` is the dry run (`GET /reconcile`): for every owned or adopted resource and every orphan
directory it says what the policy does and why. Unowned resources are not in it at all.

| verdict | when | what happens |
|---|---|---|
| `auto` | labelled by CodeLoupe, its workspace has **landed**, no container of the workspace runs, older than `graceMinutes` | removed without asking, if `auto` is on |
| `confirm` | adopted by a rule; or the workspace is abandoned, an orphan, or gone from the registry; or landed but still running; or an orphan directory under a worktree root | removed only when named: `ws reconcile --confirm <key>` or `--workspace ABC-420` |
| `keep` | the workspace is active; its repository is not in the registry; younger than the grace period | stays |
| `protected` | a `protect` rule of the config matches | never touched, whatever else holds |

`--run` (or `POST /reconcile/run` with `{"confirm": [keys], "workspaces": [names]}`) does it now: the `auto` entries plus
what is named. A named `keep` or `protected` entry is refused. The plan is re-read from the registry and Docker for every
run, so a stale key removes nothing it should not. Removal goes through the Engine API, containers first (stopped, removed
with their anonymous volumes), then networks, volumes, images, never forced: a resource that is in use is *blocked*, not
killed. An orphan directory is deleted without following links; a file that is still locked (Windows) leaves it blocked.

Blocked and failed targets are retried with a growing wait (`retryBaseMinutes`, doubling up to `retryMaxMinutes`), kept
in `<home>/reconcile-state.json`, so the backoff survives a restart of the daemon or the PC. A removal someone confirmed
is retried without a second confirmation. With `auto` on the daemon runs the `auto` entries shortly after it starts, after
a job finished, every `intervalMinutes` while a client has called the daemon in the last 15 minutes, and whenever a retry
falls due. Every attempt is written to `daemon.log` and `<home>/reconcile.jsonl` and emitted as a `reconcile.action` event.

**Release instead of cleanup.** `codeloupe ws release <worktree directory | worktree name | task id> [--repo <path>]`
(`POST /workspaces/release`) marks a workspace released and returns at once, whatever Docker or a lock is doing: it writes
one small file (`<home>/releases.json`) and wakes the reconciler in the background. Every resource of that workspace,
labelled or adopted, whatever state the workspace is in and whether its containers run, becomes an `auto` entry
(`released` in the plan) and is removed with the usual retries, **also with `auto` off**: the release is the
confirmation. A protect rule still wins. A mark covers what the workspace had created up to the release, so a new
workspace of the same name is not cleaned by an old mark, and it goes when nothing of it is left (or after 30 days).
`ws release --list` (and `releases` in `codeloupe status`) shows what is left of each release and what is retrying.
The main worktree cannot be released. A close-out step calls `ws release` instead of `docker compose down` and the
cleanup of leftovers.

```json
{ "workspaces": { "reconcile": { "auto": true, "intervalMinutes": 30, "graceMinutes": 60, "retryBaseMinutes": 1, "retryMaxMinutes": 360,
  "protect": [ { "match": "^acme-app(_|$)" }, { "match": "^acme-app_postgres-data$", "kinds": ["volume"] } ] } } }
```

`auto` is off by default. `protect` patterns are regular expressions tried (case-insensitively, anywhere in the name,
so anchor them) against each name of a resource and its compose project; without `kinds` they also cover directories.

## Processes and build daemons per workspace

`codeloupe ws processes [--workspace ABC-5] [--json]` (`GET /processes`) lists the processes that work in a workspace
directory and the memory each workspace holds. A process belongs to the workspace whose directory holds its working directory
(the deepest one when worktrees sit inside the main checkout), or else the one whose path appears in its command line (at a path
boundary: `ABC-5` is not `ABC-50`). The working directory comes from the process itself: `/proc/<pid>/cwd` on Linux, `lsof` on
macOS, the PEB of the process on Windows. Another user's or a protected process is not seen. A Gradle daemon works in the project's
directory only while a build runs and goes back to its own directory after it, so an idle daemon is placed by its own log
(`<gradle user home>/daemon/<version>/daemon-<pid>.out.log`: the directory of the last `Received command: Build{…}` and the last
`Marking the daemon as busy / idle`; the Gradle user home is `workspaces.gradleUserHome`, `GRADLE_USER_HOME` or `~/.gradle`).
An idle daemon left behind by a finished task is what keeps hundreds of MB, and on Windows what keeps the worktree directory
from being deleted, which is why the reconciler stops it. `via` in the output says whether a process was placed by its
`cwd`, by its `last build` or by its `command line`.

The reconciler plans **build tools only**: Gradle daemons and workers, and the Kotlin compile daemon. Other processes (an editor, a
shell, a dev server) are listed and never touched. A build tool of a **released** workspace is an `auto` entry (`process:<pid>:<start>`)
and is stopped without asking, also with `auto` off, once it is checked again at the moment of the stop: it is the same process (pid
and start time), still a build tool, still placed in that workspace, **idle** (Gradle's own log does not mark it busy, and neither it nor
its children use CPU for 0.6 s) and no `gradlew` build runs in that workspace. The Kotlin daemon serves every workspace, so it waits for any running Gradle build. A process
that fails these checks is *blocked* and retried with the usual backoff. Processes of a workspace that landed, was abandoned or is an
orphan are `confirm` entries (`ws reconcile --confirm process:…`), those of an active workspace are kept. A `protect` rule that
matches the command line, the working directory or the workspace path keeps a process untouched. Only processes of registered
workspaces are ever considered, so processes of other projects are not.

## Ports per workspace

With `"ports": { "range": [19000, 19999] }` under `workspaces`, a workspace asks for a port by name:
`codeloupe ws ports allocate app` prints the port of `app` in the workspace of the directory (the same name always gets the
same one; `postgres`, `web`, … get others). A new port is one that nothing listens on (it is bound and connected to), no
container publishes and no workspace has recorded, so it never collides with a live listener; the allocation is a record
in `<home>/ports.json`, it holds nothing open. `codeloupe ws ports` (and `GET /ports`, for the app) lists every allocation
with what holds it now: `free`; `in-use` by the workspace's own container or by a process whose command line names the
workspace; or `conflict` with the owning container (and its workspace, or none) or the process (pid and command line, from
`netstat` / `ss` / `lsof`). Ports of the range held by something that is no workspace's are listed as `foreign`. `ws ports free [name]` forgets a port, and `ws release` frees all of the
workspace's. `codeloupe status` shows `portAllocations`.
