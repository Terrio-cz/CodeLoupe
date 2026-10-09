# Security policy

## Supported versions

CodeLoupe is pre-release (version 0.1.0, no public release yet). Only the latest `main` and, once releases exist, the
latest release get security fixes; there are no maintained older branches.

## Reporting a vulnerability

Please report a vulnerability privately, not in a public issue, discussion or pull request.

1. Use GitHub's private vulnerability reporting: go to the repository's **Security** tab and choose **Report a
   vulnerability**, or open <https://github.com/Terrio-cz/CodeLoupe/security/advisories/new>.
2. If that page is not available (the feature is a repository setting and may be off), open an
   [issue](https://github.com/Terrio-cz/CodeLoupe/issues/new) titled "Security contact request" that contains **no**
   details of the problem, only that you have a security report. A maintainer will then arrange a private channel with
   you.

A useful report says which component and version is affected, how to reproduce it (the smallest steps or proof of
concept), what an attacker gains and what they need first (local access, a malicious repository, a malicious
network peer). Do not include real secrets, tokens or private code; use throwaway values.

## What to expect

CodeLoupe is maintained by a small team. We aim to acknowledge a report within a week, to tell you whether we consider it
a vulnerability and how we plan to fix it, and to credit you in the advisory if you wish. These are goals, not
guarantees, and fix times depend on severity and complexity. Please give us a reasonable time to fix a problem before you
disclose it publicly; we will publish a GitHub security advisory when a fix is available.

## Scope

In scope:

- the daemon and its local HTTP API and MCP endpoint (request validation, the `Host`, `Origin` and header checks, the job
  runner and its policy hook, the file edit tool);
- the encrypted secret store (`codeloupe env`, `GET /env/values`, the audit log) and anything that could expose a stored
  value;
- the Claude Code plugin's hooks and the commands they run;
- the updater and the installers (feed and checksum verification, what the installer replaces);
- the Electron desktop app (sandbox, IPC validation, the actions it takes through the daemon and the `claude` CLI);
- the build and release workflows in `.github/`.

Out of scope: problems that need an attacker who already runs code as the same user on the machine (that user can read
the daemon's home anyway), vulnerabilities in third-party dependencies with no effect on CodeLoupe (report those
upstream; automated dependency updates cover the rest), denial of service by a user against their own machine, and
issues in software that merely sits next to CodeLoupe (a tracker, Docker, Claude Code itself).

## The security model in short

The daemon is local-only: it listens on `127.0.0.1`, refuses requests with a foreign `Host`, any `Origin` or without the
`x-codeloupe` header, so it serves no remote clients, and every route that acts for the user (running commands, jobs, releases, the app's
views) wants the token in `<home>/daemon.token`, which only the owner can read, so another user of the machine cannot call them. The secret store is encrypted with a key that only the operating
system (or a passphrase) can open; the tools an agent calls never return a value, and a value is not passed as an
argument or written to a log. The
desktop app's renderer is sandboxed and talks to the daemon only through validated IPC. Details are documented in the
wiki, in [Configuration](https://github.com/Terrio-cz/CodeLoupe/wiki/Configuration),
[Environment and secrets](https://github.com/Terrio-cz/CodeLoupe/wiki/Environment-and-secrets) and
[Installers and updates](https://github.com/Terrio-cz/CodeLoupe/wiki/Installers-and-updates), and in the Security
section of [app/README.md](app/README.md).
