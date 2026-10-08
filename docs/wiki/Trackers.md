With a tracker configured, CodeLoupe mirrors its issues into a local SQLite database and answers issue reads and task
queries from it, in milliseconds and without a request: the [tracker tools](Tools-reference#tracker-tools) (`issue`,
`tasks`, `similar`, `task_context`, `dispatch_plan`, `update`) work on the mirror, and `task_code` joins tasks to the
code that landed for them. YouTrack is the supported tracker. Without a tracker none of this is on offer and the
code tools work as usual.

## Configure a tracker

In `<home>/config.json` ([Configuration](Configuration)):

```json
{
  "trackers": [{
    "name": "acme", "type": "youtrack", "url": "https://acme.youtrack.cloud", "projects": ["ABC", "OPS"],
    "token": { "env": "YOUTRACK_TOKEN" },
    "repos": ["C:/src/acme"]
  }]
}
```

The token comes from an environment variable of the daemon (`{ "env": "NAME" }`; on Windows the user's persistent
variables, not the shell that happened to start it, see [Jobs and events](Jobs-and-events)) or a
`KEY=value` file (`{ "dotenv": "<path>", "key": "NAME" }`), read by the daemon on each request; it never appears in
answers, errors, `/status` or logs. The desktop app can also keep a YouTrack token in the secret store and add the
instance itself ([Desktop app](Desktop-app#accounts)).

## How the mirror stays current

- The mirror (`<home>/trackers/<name>.db`, SQLite + FTS5) loads each project once, then a watcher asks only for issues
  whose `updated` moved, and only while tool calls arrive: no client, no polling. Syncing runs every 3 minutes while
  clients are active and stops 10 minutes after the last tool call (`trackerSyncMinutes`, `trackerIdleMinutes`).
- A read more than 30 s after the project's last sync checks that one issue's `updated` first.
- The mirror only reads. `update` is the one way CodeLoupe writes to the tracker (YouTrack: field values, `summary`,
  `description`, comments), with the token's own permissions; the mirror stores the tracker's own answer to the write.
- `repos` are git repositories whose worktree branch names (`ABC-5`, `feature/ABC-5-x`) mark tasks as taken for
  `tasks mode=ready`, besides every repository the daemon has indexed.

## Tasks and code

Task ids follow the tracker's projects, or `taskPattern` (a regular expression) in a repository's `.codeloupe.json`.
`task_code` reads the default branch's history and works without a tracker; with one it adds the issue text, from which
it predicts the touch set of an open task. `dispatch_plan` compares those sets between tasks and with every live
worktree so that no two windows edit the same code.
