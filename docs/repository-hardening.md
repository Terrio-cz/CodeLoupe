# Repository hardening: what the owner applies

Everything that can be enforced by a file is already in the repository (see [Security](https://github.com/Terrio-cz/CodeLoupe/wiki/Security)).
What is left lives in the repository settings and needs admin rights; a pull request cannot set it, and the agents that work in this
repository are told not to touch it. State below was read with `gh api` on 2026-10-09 (read-only).

Why the order matters: other windows land work by merging `origin/main` and pushing straight to `main`. A ruleset with required checks
rejects a push of a commit that has not passed them, so the `main` ruleset below keeps the *Repository admin* role as a bypass actor.
Drop the bypass once the landing flow pushes a branch first and waits for green.

## Checklist

| # | Setting | Now | Wanted |
|---|---|---|---|
| 1 | Ruleset for `main` | none; legacy branch protection forbids force-push and deletion, requires nothing | block force-push and deletion, require the checks of row 2, bypass actor *Repository admin* (mode *always*). No pull request or review requirement. |
| 2 | Required checks | none | `tools`, `test (ubuntu-latest)`, `test (windows-latest)`, `test (macos-latest)`, `app (ubuntu-latest)`, `bundle (ubuntu-latest)`, `analyze (java-kotlin)`, `analyze (javascript-typescript)` (the names GitHub shows today; a renamed job stops matching) |
| 3 | Ruleset for tags `v*` | none | creation, update and deletion restricted; bypass actor *Repository admin* so the maintainer can still tag a release |
| 4 | Actions policy | all actions allowed, SHA pinning not required | allow GitHub-owned actions and `gradle/actions/*`, require full-length commit SHAs (every workflow already pins them; `node tools/check-workflows.mjs` guards it) |
| 5 | Fork pull requests | approval for first-time contributors | approval for all outside collaborators |
| 6 | Immutable releases | off | on, before the first release is published |
| 7 | `release` environment | not created yet (the first tag creates it without rules) | required reviewer = the maintainer, deployment limited to tags `v*`; the `publish` job then waits for an approval |
| 8 | Private vulnerability reporting | off | on (`SECURITY.md` sends reporters there and has a fallback while it is off) |
| 9 | Wiki | editing is open to anyone with the Wikis feature on | Settings, Features, Wikis: tick *Restrict editing to users in teams with push access only* |
| 10 | Contribution licence sentence, `CODEOWNERS` | written by the maintainer's agents | read `CONTRIBUTING.md`, the pull request template and `.github/CODEOWNERS` once and confirm them |
| 11 | Already as wanted | secret scanning and push protection, Dependabot alerts and security updates, read-only workflow token default, CodeQL through `codeql.yml` | no change |

## Commands

Run as the repository owner with `gh` signed in as an administrator of the repository. Check each setting afterwards with the `GET`
form of the call or in the web interface.

```bash
# 1-2: ruleset for the default branch (role id 5 is "Repository admin")
gh api -X POST repos/Terrio-cz/CodeLoupe/rulesets --input - <<'JSON'
{
  "name": "main", "target": "branch", "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "bypass_actors": [{ "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" }],
  "rules": [
    { "type": "deletion" }, { "type": "non_fast_forward" },
    { "type": "required_status_checks", "parameters": { "strict_required_status_checks_policy": false, "required_status_checks": [
      { "context": "tools" }, { "context": "test (ubuntu-latest)" }, { "context": "test (windows-latest)" }, { "context": "test (macos-latest)" },
      { "context": "app (ubuntu-latest)" }, { "context": "bundle (ubuntu-latest)" },
      { "context": "analyze (java-kotlin)" }, { "context": "analyze (javascript-typescript)" }
    ] } }
  ]
}
JSON

# 3: ruleset for release tags
gh api -X POST repos/Terrio-cz/CodeLoupe/rulesets --input - <<'JSON'
{
  "name": "release tags", "target": "tag", "enforcement": "active",
  "conditions": { "ref_name": { "include": ["refs/tags/v*"], "exclude": [] } },
  "bypass_actors": [{ "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" }],
  "rules": [{ "type": "creation" }, { "type": "update" }, { "type": "deletion" }]
}
JSON

# 4-5: Actions policy
gh api -X PUT repos/Terrio-cz/CodeLoupe/actions/permissions -F enabled=true -f allowed_actions=selected -F sha_pinning_required=true
gh api -X PUT repos/Terrio-cz/CodeLoupe/actions/permissions/selected-actions -F github_owned_allowed=true -F verified_allowed=false -f 'patterns_allowed[]=gradle/actions/*'
gh api -X PUT repos/Terrio-cz/CodeLoupe/actions/permissions/fork-pr-contributor-approval -f approval_policy=all_external_contributors

# 6: immutable releases; 8: private vulnerability reporting
gh api -X PUT repos/Terrio-cz/CodeLoupe/immutable-releases
gh api -X PUT repos/Terrio-cz/CodeLoupe/private-vulnerability-reporting

# 7: the release environment (reviewer id: gh api users/<login> --jq .id)
gh api -X PUT repos/Terrio-cz/CodeLoupe/environments/release --input - <<'JSON'
{ "reviewers": [{ "type": "User", "id": 0 }], "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true } }
JSON
gh api -X POST repos/Terrio-cz/CodeLoupe/environments/release/deployment-branch-policies -f name='v*' -f type=tag
```

Replace the `0` of the reviewer by the maintainer's numeric user id. If a call answers 404 or 422, the setting is not available for
this repository or plan; apply it in the web interface (Settings, Rules; Settings, Actions, General; Settings, Environments).

## After the first release

1. Run `gh attestation verify <file> --repo Terrio-cz/CodeLoupe` on one asset and `sha256sum -c SHA256SUMS.txt`
   ([Security](https://github.com/Terrio-cz/CodeLoupe/wiki/Security#verifying-a-release)). The `publish` job has never run against a
   real tag, so this is the first proof that the attestation is created; if it is not, the job log says which permission is missing.
2. Publish the draft only when row 6 is on, so that the published release is immutable.
3. The updater still trusts whatever the release holds (a SHA-512 from the same release). A detached signature over `latest.yml` closes
   that and needs a key pair kept by the owner: see "How far the updater is trusted" in the Security wiki page.
