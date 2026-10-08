# Code signing and notarisation (CL-105)

Status: **decided 2026-10-08 by the owner: nothing is paid for.** The installers stay unsigned, with checksums and SBOMs,
and the free routes below make that as painless as it can be. The options and costs stay written down in case that
changes. Unsigned means: Windows shows "unknown publisher" (SmartScreen) for a browser download, macOS refuses to open
the app without a manual override, and macOS cannot update itself silently (CL-107 covers what is possible).

## The free path (what the project does)

- **Release assets**: installers, bundle zips, `SHA256SUMS.txt`, CycloneDX SBOMs and the notes, published by hand from
  the draft release ([release.md](release.md)). The build runs in public CI from a tag.
- **Windows**: SmartScreen judges files that carry the Mark of the Web, which a browser download has and a download by a
  package manager does not. The release pipeline generates a **winget manifest** and a **Scoop manifest** from the
  published files and their SHA-256; installing through them avoids the warning. Browser downloads: "More info → Run
  anyway".
- **macOS**: the app is signed **ad hoc** (free, `codesign --sign -`), the minimum Apple Silicon needs to run it at all;
  Gatekeeper still asks for a manual allow once (right-click → Open, System Settings → Privacy & Security → Open Anyway,
  or `xattr -dr com.apple.quarantine /Applications/CodeLoupe.app`). A **Homebrew cask** generated from the release
  removes the quarantine flag on install. macOS does not update itself: the app tells the user that a new version exists
  and links to the release.
- **Linux**: nothing needed; AppImage and `.deb` with checksums.
- **Updates**: Windows (NSIS) and Linux (AppImage) update themselves with `electron-updater`, which checks the SHA-512 in
  the release feed over HTTPS; macOS only notifies (CL-107).
- Publishing the manifests to winget-pkgs, a Scoop bucket or a Homebrew tap is an outward-facing step the owner takes;
  the repository only generates the files.
- **No free certificate fits**: SignPath Foundation signs open-source projects with an OSI-approved licence for free;
  CodeLoupe's PolyForm Noncommercial 1.0.0 is not OSI-approved. If the licence ever changes, apply there.

## What each platform needs for a clean install

| Platform | Needed for a clean install | What happens without it |
|---|---|---|
| Windows | An Authenticode signature on the installer and the executables. Reputation builds per file hash, so every new build starts with a SmartScreen warning that fades with downloads; an EV certificate no longer skips it (Microsoft removed the EV special case in 2024). | "Windows protected your PC", install possible through "More info → Run anyway". Auto-update by `electron-updater` refuses an unsigned update (it verifies the publisher). |
| macOS | A Developer ID Application certificate, the hardened runtime, notarisation by Apple and stapling. Only a paid Apple Developer Program membership can issue it. | Gatekeeper refuses the app; the user must remove the quarantine flag by hand. Auto-update on macOS needs a signed, notarised app. |
| Linux | Nothing required. AppImage and `.deb` ship with `SHA256SUMS.txt` from the release pipeline, see [release.md](release.md). | — |

## Options that cost money, not chosen (prices from vendor pages, checked 2026-10-08; they vary by reseller and date)

### Windows

| Option | Cost | Fits CodeLoupe? |
|---|---|---|
| **Azure Artifact Signing** (formerly Trusted Signing), Basic plan | about 9.99 USD/month for 5 000 signatures, 0.005 USD per extra signature. Public-trust certificates for **organisations** in the USA, Canada, the EU and the UK and for **individual developers in the USA and Canada only**; the name on the certificate is the validated legal name, no custom display name. The key stays in Microsoft's HSM, signing runs from GitHub Actions with OIDC, no secret in the repository. | Cheapest paid route and no hardware token. Works only if the publisher is a registered organisation in a supported country (or a US/Canadian individual). Identity validation takes days and must be renewed. |
| **OV code-signing certificate** from a reseller (Sectigo, SSL.com, DigiCert) | roughly 130–440 USD/year (SSL.com from about 129, Sectigo about 220, DigiCert about 440); since June 2023 the key must live on hardware (token or cloud HSM, e.g. SSL.com eSigner with a monthly signing fee on top); validity capped at 460 days since March 2026. | Works for any legal entity or individual the CA validates. Cloud HSM (eSigner and similar) is required to sign from GitHub-hosted runners. Highest running cost and the most paperwork. |
| **EV certificate** | roughly 290–650 USD/year plus hardware key. | Not worth it for SmartScreen any more; useful only if a customer's procurement asks for EV. |
| **SignPath Foundation** (free for open source) | free; signs from CI with the key in their HSM; the signer shown is "SignPath Foundation". | The programme is for open-source projects with an OSI-approved licence. CodeLoupe is source-available under PolyForm Noncommercial 1.0.0, which is not OSI-approved, so it most likely does not qualify; check their current terms before applying. |
| **Microsoft Store** (MSIX) | one-time developer registration fee, Microsoft re-signs the package and the warning disappears. | Changes the packaging (MSIX instead of NSIS) and puts Microsoft's review in the release path; the bundled daemon and the Claude Code plugin install make that heavier than it looks. Not recommended for 1.0. |

### macOS

| Option | Cost | Notes |
|---|---|---|
| **Apple Developer Program** | 99 USD/year (no waiver for individuals or one-person businesses) | The only way to get a Developer ID certificate and notarise. Notarisation itself is free and takes minutes. `electron-builder` signs and notarises with `notarytool` from environment variables: an App Store Connect API key (`APPLE_API_KEY`, `APPLE_API_KEY_ID`, `APPLE_API_ISSUER`) is preferred over an app-specific password. The hardened runtime and entitlements for the Electron helper processes must be configured (the daemon is a separate bundled executable and has to be signed with the same identity). |

## If the decision changes

- macOS needs the Apple Developer Program (99 USD/year); there is no cheaper route. Windows: Azure Artifact Signing Basic
  (about 10 USD/month) for an organisation in the USA, Canada, the EU or the UK, otherwise an OV certificate with a cloud HSM
  (about 130–220 USD/year plus the signing fee); EV is not worth it. Together roughly 220–450 USD/year.
- Signing is then configuration, not code: `win.azureSignOptions` or `win.signtoolOptions`, `mac.hardenedRuntime`,
  `mac.notarize`, an entitlements file, and a signing step in the release workflow behind a protected `release`
  environment (required reviewers, tag-only deployments) holding the secrets; the bundled `java` runtime and launcher
  must be signed with the same macOS identity. `app/electron-builder.yml` now sets `identity: null` on macOS.
- After signing: install on clean Windows and macOS machines, then macOS can update itself silently.

Sources: [Azure Artifact Signing pricing](https://azure.microsoft.com/pricing/details/artifact-signing/),
[Artifact Signing FAQ](https://learn.microsoft.com/azure/trusted-signing/faq),
[Microsoft: code signing options](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options),
[SignPath for open source](https://about.signpath.io/product/open-source),
[Apple Developer Program](https://developer.apple.com/programs/).
