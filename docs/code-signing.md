# Code signing and notarisation (CL-105)

Status: options and costs written down (2026-10-08); the choice is the owner's. Until one is made the installers are
unsigned: Windows shows "unknown publisher" (SmartScreen), macOS refuses to open the app without a manual override,
and `electron-updater` cannot verify an update (CL-107 waits for this card).

## What each platform needs

| Platform | Needed for a clean install | What happens without it |
|---|---|---|
| Windows | An Authenticode signature on the installer and the executables. Reputation builds per file hash, so every new build starts with a SmartScreen warning that fades with downloads; an EV certificate no longer skips it (Microsoft removed the EV special case in 2024). | "Windows protected your PC", install possible through "More info → Run anyway". Auto-update by `electron-updater` refuses an unsigned update (it verifies the publisher). |
| macOS | A Developer ID Application certificate, the hardened runtime, notarisation by Apple and stapling. Only a paid Apple Developer Program membership can issue it. | Gatekeeper refuses the app; the user must remove the quarantine flag by hand. Auto-update on macOS needs a signed, notarised app. |
| Linux | Nothing required. AppImage and `.deb` ship with `SHA256SUMS.txt` from the release pipeline, see [release.md](release.md). | — |

## Options and costs (prices from vendor pages, checked 2026-10-08; they vary by reseller and date)

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

## Recommendation

1. **macOS: enrol in the Apple Developer Program (99 USD/year)** — there is no cheaper route, and without it the macOS
   installer cannot be opened normally.
2. **Windows: Azure Artifact Signing Basic (about 10 USD/month) if the publisher is an organisation in the USA, Canada, the
   EU or the UK** (Terrio-cz as a registered company qualifies if it is one); otherwise an OV certificate with a cloud HSM
   from SSL.com or a similar CA (about 130–220 USD/year plus the signing fee). Do not buy EV.
3. Total running cost for both platforms is about 220–330 USD/year with Artifact Signing, 230–450 USD/year with an OV
   certificate. Expect Windows SmartScreen warnings to continue on the first releases whatever is chosen: reputation
   builds per file hash.
4. Secrets live only in GitHub environment secrets of a protected `release` environment (required reviewers, tag-only
   deployments), never in the repository; Windows signing with Artifact Signing needs none (OIDC federation).

## What the owner has to decide and provide

- Whether to sign at all for 1.0, and which Windows route (legal entity: who is the publisher named in the certificate).
- An Apple Developer account (the team ID and an App Store Connect API key stored as environment secrets) and, for
  Windows, the Azure subscription or the certificate vendor account.

## What is prepared in the repository

- `app/electron-builder.yml` has no certificate configured (`identity: null` for macOS); the release job in
  [release.md](release.md) builds unsigned installers and checksums.
- Once the secrets exist, signing is configuration, not code: `win.azureSignOptions` (Artifact Signing) or
  `win.signtoolOptions`, `mac.hardenedRuntime`, `mac.notarize`, the entitlements file, and a signing step in the release
  workflow guarded by the protected environment. The daemon's bundled `java` runtime and launcher must be signed with the
  same macOS identity or Gatekeeper rejects the app.
- Acceptance run after signing: install on clean Windows and macOS machines, SmartScreen/Gatekeeper pass, then CL-107
  (auto-update) can verify updates.

Sources: [Azure Artifact Signing pricing](https://azure.microsoft.com/pricing/details/artifact-signing/),
[Artifact Signing FAQ](https://learn.microsoft.com/azure/trusted-signing/faq),
[Microsoft: code signing options](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options),
[SignPath for open source](https://about.signpath.io/product/open-source),
[Apple Developer Program](https://developer.apple.com/programs/).
