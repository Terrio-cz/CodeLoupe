What protects what in the repository and in a release, how to verify a release, how far the updater is trusted, and the repository settings the owner still has to apply (they cannot be set from a pull request). The local API is covered in [Configuration](Configuration#who-may-call-the-daemon), the secret store in [Environment and secrets](Environment-and-secrets), reporting a problem in the [security policy](https://github.com/Terrio-cz/CodeLoupe/blob/main/SECURITY.md).

## What the repository files enforce

| Rule | Where | Checked by |
|---|---|---|
| Every workflow has a top-level `permissions:` block (read-only by default); a job that needs more asks for it on the job | `.github/workflows/*.yml` | `node tools/check-workflows.mjs`, in the `tools` job of every push |
| Every action is pinned to a full commit SHA, never a tag | the same | the same, and Dependabot proposes the update (after a 7-day cooldown) |
| `actions/checkout` leaves no token on disk; no `pull_request_target`; nothing an outsider controls inside a `run:` block | the same | the same |
| A pull request that adds a vulnerable dependency (severity high or worse) does not pass | `dependency-review.yml` | GitHub's dependency review |
| A release is built from a tag that is on `main`; the build job holds a read-only token and runs the third-party code, the `publish` job holds the write token and runs nothing but `gh` | `release.yml` | the workflow itself |
| The release is a **draft**; publishing it is a person's decision. The `publish` job runs in the `release` environment, where the owner can require an approval (below) | `release.yml` | GitHub |
| Every file of a release carries a signed build provenance attestation (SLSA, Sigstore) | `release.yml`, `actions/attest` | `gh attestation verify` |
| SBOMs (CycloneDX) of the daemon and of what the app ships, `SHA256SUMS.txt`, and an update feed that must match the installers | `release.yml`, `tools/feed-check.mjs` | the release job |
| `CODEOWNERS` names the maintainer for everything; `SECURITY.md` says how to report privately | `.github/CODEOWNERS`, `SECURITY.md` | |
| The Gradle distribution is pinned by checksum | `gradle/wrapper/gradle-wrapper.properties` | Gradle |

## Verifying a release

```bash
sha256sum -c SHA256SUMS.txt                       # in the folder with the downloaded files
gh attestation verify CodeLoupe-<version>-linux-x86_64.AppImage --repo Terrio-cz/CodeLoupe
```

The second command checks the Sigstore signature of the provenance and prints the workflow, the tag and the commit the file
was built from. A file that was replaced after the build, or built anywhere else, fails it. The release notes repeat both lines.

## How far the updater is trusted

What exists, and costs nothing: the app asks only `github.com/Terrio-cz/CodeLoupe` (the origin is a constant, not a setting; a
redirect GitHub answers a download with is followed), over HTTPS; it reads `latest.yml` / `latest-linux.yml` of one release,
downloads the installer, and installs it only if its **SHA-512 and size** match the feed; it never installs an older version than
the running one; the release job refuses a feed that does not match the installers beside it; the previous bundle comes back if the
new daemon does not answer in 90 seconds.

What it does not give: the feed and the installer come from the same release, so whoever can change a release's files (a stolen
maintainer token, a hijacked `publish` job) can change both, and the app has no key of its own to tell. A signature that the app
checks against a key it already holds is what closes that, and the two ways to have one are open:

- **Authenticode (Windows) and Developer ID (macOS)** need a certificate, which costs money; the owner decided on 2026-10-08 to pay
  for nothing ([code-signing.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/code-signing.md)). Open until that changes.
- **A detached signature over `latest.yml`** (a key pair of the project, public key shipped in the app, signature verified before
  the download) costs nothing but needs the private key kept where the release job can use it without the build code reaching it;
  not done. Until then the provenance attestation above is the check a person can make by hand, and **immutable releases**
  (checklist below) keep a published release from being changed afterwards.

## Checklist for the owner

State read with `gh api` on 2026-10-09 (read-only). Nothing here is changed by the repository files, because other windows land
directly on `main` and a ruleset would change how.

| Setting | Now | Wanted |
|---|---|---|
| Ruleset for `main` | none; legacy branch protection forbids force-push and deletion, requires nothing | add a ruleset: block force-push and deletion, require the status checks below. **Required checks stop a direct push of a commit that has not passed them**, which the current landing (merge `origin/main`, push straight to `main`) always does: add *Repository admin* as a bypass actor (mode *always*) to keep it, and drop the bypass once the flow pushes the branch first and waits for green. No pull request or review requirement. |
| Required checks | none | `tools`, `test (ubuntu-latest)`, `test (windows-latest)`, `test (macos-latest)`, `app (ubuntu-latest)`, `bundle (ubuntu-latest)`, `analyze (java-kotlin)`, `analyze (javascript-typescript)` (the names GitHub shows today; a renamed job stops matching) |
| Ruleset for tags `v*` | none | restrict creation to the maintainer, block update and deletion (a tag is what starts a release) |
| Actions policy | all actions allowed; SHA pinning not required | *Allow GitHub-owned actions and those listed*: `gradle/actions/*` is the only other owner in use; turn on *Require actions to be pinned to a full-length commit SHA* (every workflow already is) |
| Fork pull requests | approval for first-time contributors | approval for **all outside collaborators** |
| Workflow token default | read-only; cannot approve pull requests | as is |
| Immutable releases | off | on: a published release's files and tag can no longer change |
| `release` environment | not created yet (the first tag creates it without rules) | *Settings → Environments → release*: required reviewer = the maintainer; limit to tags `v*`. The `publish` job then waits for an approval |
| Private vulnerability reporting | off | on (SECURITY.md sends reporters there and has a fallback while it is off) |
| Secret scanning, push protection | on | as is; optionally *validity checks* and *non-provider patterns* (off) |
| Dependabot alerts and security updates | on | as is |
| Code scanning | CodeQL by `codeql.yml` (default setup off) | as is |

A ruleset can be created from the command line, for example (adjust the bypass actor to your role id; `5` is *Repository admin*):

```bash
gh api -X POST repos/Terrio-cz/CodeLoupe/rulesets --input - <<'JSON'
{
  "name": "main", "target": "branch", "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "bypass_actors": [{ "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" }],
  "rules": [
    { "type": "deletion" }, { "type": "non_fast_forward" },
    { "type": "required_status_checks", "parameters": { "strict_required_status_checks_policy": false, "required_status_checks": [
      { "context": "tools" }, { "context": "test (ubuntu-latest)" }, { "context": "app (ubuntu-latest)" }, { "context": "bundle (ubuntu-latest)" }
    ] } }
  ]
}
JSON
```
