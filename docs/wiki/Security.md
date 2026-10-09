What protects what in the repository and in a release, how to verify a release, how far the updater is trusted, and where the repository settings the owner still has to apply are listed (they cannot be set from a pull request). The local API is covered in [Configuration](Configuration#who-may-call-the-daemon), the secret store in [Environment and secrets](Environment-and-secrets), reporting a problem in the [security policy](https://github.com/Terrio-cz/CodeLoupe/blob/main/SECURITY.md).

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

What a SHA-512 does not give: the feed and the installer come from the same release, so whoever can change a release's files (a stolen
maintainer token, a hijacked `publish` job) can change both. A signature that the app checks against a key it already holds is what
closes that, and there are two ways to have one:

- **Authenticode (Windows) and Developer ID (macOS)** need a certificate, which costs money; the owner decided on 2026-10-08 to pay
  for nothing ([code-signing.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/code-signing.md)). Open until that changes.
- **A detached signature over the feed** costs nothing and is built (CL-174), but is off until the owner has made the key pair. The
  `publish` job signs `latest.yml` and `latest-linux.yml` with an Ed25519 key kept as the `UPDATE_SIGNING_KEY` secret of the `release`
  environment (only that job reads it, and it runs nothing but `openssl` and `gh` with it; the build code never sees it) and adds
  `latest.yml.sig` and `latest-linux.yml.sig` to the release, base64 of the raw signature. The app embeds the matching public keys
  (`app/src/main/update/updateKeys.ts`; a list, so a new key can ship before the old one is dropped) and, before it reads a feed,
  fetches the `.sig` beside it and verifies it over the exact text it then parses. A feed with no signature, a signature by a key
  the build does not list (an old key included) or a feed changed after signing is refused with the reason in the update log, and
  no installer is requested. **While the list is empty** a build checks only the SHA-512 as before and writes
  `update feeds are not signature-checked` to `update.log`; the release workflow then prints a notice that the feeds were not signed.
  Rotating a key by signing the new one with the old one is not built: ship the new public key next to the old in one release,
  and drop the old in the next. The owner's steps (key pair, secret, public key in the constant) are in
  [repository-hardening.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/repository-hardening.md).

Until a key is embedded the provenance attestation above is the check a person can make by hand, and **immutable releases**
(see Repository settings below) keep a published release from being changed afterwards.

## Repository settings

A few protections are repository settings, not files, and an owner with admin rights has to apply them (rulesets for `main` and for
`v*` tags, the Actions policy, immutable releases, the `release` environment, private vulnerability reporting). They are listed with the
current state and the commands in [repository-hardening.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/repository-hardening.md).
