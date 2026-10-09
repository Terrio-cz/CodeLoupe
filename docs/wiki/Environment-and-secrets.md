Keep secrets and variables in one encrypted store instead of `.env` files scattered over workspaces. An agent can name them and run a command with them, and never sees a value.

One store for the variables and secrets that Claude workspaces, MCP servers and scripts use, so that they live in one place
and an agent never sees a value. `<home>/secrets/vault.env` (JSON) holds one AES-256-GCM ciphertext per name and scope, bound
to its name and scope, and a random data key that only the OS can open: Windows DPAPI (current user), the macOS login
Keychain, libsecret (`secret-tool`) on Linux, else a key derived from the passphrase in `CODELOUPE_PASSPHRASE` (PBKDF2-HMAC-SHA256,
600 000 rounds for a new vault). On macOS the key reaches the `security` tool on its standard input, never on a command line. A vault is always opened the way it was made. The file name ends in `.env` on purpose: the workspace rules that
deny reading `*.env` cover it.

**Where the vault lives.** It is in the daemon's home, which is a cache folder on macOS (`~/Library/Caches/codeloupe`) and Linux
(`~/.cache/codeloupe`); Windows uses `%LOCALAPPDATA%`, which cleaners leave alone. A cache cleaner that empties the folder deletes the
vault, and on macOS and Linux the key in the keychain or secret service is left behind. `codeloupe env set` says so when it creates a vault there
(the desktop app shows the same note). Keep a copy of `<home>/secrets/`, or set `CODELOUPE_HOME` to a folder outside the cache before you store
more (the daemon, the CLI and the app must all use it). Moving the vault by default needs a migration across versions of the daemon, the CLI and the app that
share it, and is not done.

**Copying a value from the app.** The desktop app copies a stored value to the clipboard only after the OS asked you to authenticate (Touch ID on macOS;
the other systems have no prompt Electron can raise, so the button is absent there) and clears it a minute later if it is still there. The copy carries
the marker that password managers set, so clipboard history and managers skip it: on Windows `ExcludeClipboardContentFromMonitorProcessing`,
`CanIncludeInClipboardHistory` = 0 and `CanUploadToCloudClipboard` = 0 (Win+V history, cloud clipboard); on macOS `org.nspasteboard.ConcealedType`
(clipboard managers, Universal Clipboard); on Linux `x-kde-passwordManagerHint` = `secret` (KDE Klipper; other managers decide for themselves). If
the platform refuses the marked item the value is copied as plain text, as before. A CI job copies through the app's own module in a real Electron and
reads the formats back with the platform's tools on all three systems.

| | |
|---|---|
| `codeloupe env set NAME --scope global\|workspace:<id>\|repo:<id> [--source …]` | stores or rotates a value; read from stdin (a hidden prompt on a terminal), never from an argument |
| `codeloupe env list [--workspace w] [--repo r] [--all]` | name, scope, source, created, rotated, last use and by what — metadata the file holds in the clear; no key is touched |
| `codeloupe env unset NAME --scope …` | removes it |
| `codeloupe env run [--workspace w] [--repo r] -- <command>` | the command's environment gets every secret that applies (global < workspace < repository, the narrowest wins); what it prints is masked line by line of every stored value |
| `codeloupe env audit [--name N] [--scope S] [--limit 50] [--consumers] [--json]` | the append-only audit (`<home>/secrets/audit.log`): every read by consumer, creation, rotation and removal with time; `--consumers` sums up who read each name. Names and times, never a value |
| MCP tool `env` | the same names, never a value; `workspace`, `repository`, `all` |
| `codeloupe env import scan [--include-excluded] [--json]` | inventory of the variables in `.env` and docker env files, `.claude/settings*.json` `env`, `.mcp.json` and `~/.claude.json` MCP server `env` under the configured roots: name, suggested scope, every source, duplicates and conflicts (equal or different values, compared by a hash that is salted per report), what the store already holds. Never a value |
| `codeloupe env import run --select <id>[=scope] … \| --all-sensitive [--replace] [--overwrite]` | copies the selected occurrences into the store inside the process and reports created / updated / skipped; rerunning changes nothing. Two selected sources with different values for one name and scope are a conflict, stored from neither. `--replace` then swaps each imported value in its source for a reference (a comment in dotenv files, `${NAME}` in JSON) after saving an encrypted copy of the file |
| `codeloupe env import rollback <backup-id> [--force]`, `backups`, `forget <id>` | puts every replaced file back byte for byte (a file edited since is left alone unless `--force`); lists and drops the copies |
| `GET /env/values?workspace=&repository=&names=A,B` | for a local MCP server or script: the values, in its own process. Needs `x-codeloupe-env-token` (the contents of `<home>/secrets/api-token.env`, made on first use, readable by this user only) and says who asks in `x-codeloupe-used-by` |

A variable takes the scope of the folder it was found in, and the narrowest scope wins when a command runs, so a `.env` in a repository somebody else wrote
could replace your global `API_KEY` for everything run inside that folder. The inventory therefore marks a variable that would **hide** a secret the store
already holds in a wider scope (`shadows` in the JSON, *hides global key* in the app), the app leaves it unticked, and `--all-sensitive` skips it and says
which ones; `--select <id>` still imports it, as a decision. In the app, saving a key, rotating a YouTrack token and an import with *overwrite*
ask in a native dialog first, like deleting and replacing sources do.

The import looks under the roots of `envImport` in `<home>/config.json` (`{"roots":[{"path":"~/IdeaProjects","kind":"repositories"}],"exclude":["other-system"]}`; kind
`home`, `workspaces` or `repositories`). Without it: every `~/.claude*`, `~/Documents/Claude` (each folder one workspace, scope `workspace:<folder>`) and `~/IdeaProjects`
(the nearest folder with `.git`, scope `repo:<folder>`). Folders whose name holds an `exclude` word (default: none, the words are yours) are listed, not
entered, until `--include-excluded`. Templates (`.env.example`), build and dependency folders and the daemon's own home are never read. MCP `headers` and `args`,
compose `environment:` blocks and shell profiles are not scanned.

A name that has been as it is for longer than `secrets.rotationDays` in `config.json` (default 90, 0 = off) is flagged `ROTATE` in `env list`, in the `env` tool and
in the Environment screen; rotating it (`env set` again) starts the age anew. The audit keeps about 8 MB of history (`audit.log` and `audit.log.1`).

Every text that leaves the daemon (events, webhooks, summaries, `run` answers, `doc path=job:<id>`) is masked of the stored values
(six characters or more) before the pattern rules for other secret shapes, and a finished job's log file is rewritten with the
values replaced by `***`, byte exact otherwise. Masking is by value: a program that transforms a secret before printing it (base64,
split over lines) is not covered. A value on a command line is visible to this user's other processes while the command runs; use `env run`
or the API instead. A guard hook that denies reads of secret files should also cover `<home>/secrets/` (the daemon's home, `…/codeloupe/secrets/`); without one only a
`Read(**/*.env)` deny rule of Claude Code's `settings.json` covers the Read tool. The `doc` tool never reads the daemon's own folder, whatever `root` it is given, and refuses the
credential files of other tools (`.npmrc`, `.netrc`, `.git-credentials`, `.docker/`, the `gh` and `gcloud` configuration). The daemon's folder and the vault folder are created for the owner only on systems with POSIX permissions; on Windows they inherit the user-only access of `%LOCALAPPDATA%`.
