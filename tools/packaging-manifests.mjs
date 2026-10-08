#!/usr/bin/env node
// Package-manager manifests from the files of a release (CL-130): a winget manifest set, a Scoop manifest and a
// Homebrew cask, with the URLs of the release assets and the SHA-256 of the installers from SHA256SUMS.txt. Package
// managers install without the Mark of the Web (Windows SmartScreen) and the cask removes the macOS quarantine flag,
// which is what makes an unsigned release (docs/code-signing.md) painless. The release job attaches the output to the
// draft release; submitting it to winget-pkgs, a Scoop bucket or a Homebrew tap is a step the owner takes.
//
//   node tools/packaging-manifests.mjs --dir <release files> --version <v> --out <dir> [--repo Terrio-cz/CodeLoupe]
//
// <dir> holds SHA256SUMS.txt and the installers (CodeLoupe-<v>-win-x64.exe, -mac-arm64.dmg, -mac-x64.dmg). A listed
// installer that is present on disk must match its checksum. Output:
//   winget/manifests/t/Terrio/CodeLoupe/<v>/Terrio.CodeLoupe{,.installer,.locale.en-US}.yaml
//   scoop/codeloupe.json
//   homebrew/Casks/codeloupe.rb
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const VERSION = /^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$/;
const REPO = /^[\w.-]+\/[\w.-]+$/;
const WINGET_SCHEMA = '1.6.0';
const DESCRIPTION = 'On-demand code index for AI coding agents: declarations, outlines, usages and callers instead of whole files.';
// Homebrew wants under 80 characters, no full stop and nothing but the product's own words.
const CASK_DESCRIPTION = 'On-demand code index for AI coding agents';
const LICENSE_ID = 'PolyForm-Noncommercial-1.0.0';

/** `<hash>  <name>` lines of sha256sum (text or binary mode) as { name: lowercase hash }. */
export function parseChecksums(text) {
  const sums = {};
  for (const line of text.split(/\r?\n/)) {
    const m = /^([0-9a-fA-F]{64}) [ *](.+)$/.exec(line.trim());
    if (m) sums[m[2]] = m[1].toLowerCase();
  }
  return sums;
}

/** The release's coordinates: asset names, URLs and checksums of the three installers the manifests point at. */
export function releaseContext({ version, repo = 'Terrio-cz/CodeLoupe', sums }) {
  if (!VERSION.test(version)) throw new Error(`not a release version: ${version}`);
  if (!REPO.test(repo)) throw new Error(`not a repository: ${repo}`);
  const base = `https://github.com/${repo}/releases/download/v${version}`;
  const asset = suffix => {
    const name = `CodeLoupe-${version}-${suffix}`;
    if (!sums[name]) throw new Error(`SHA256SUMS.txt has no entry for ${name}`);
    return { name, url: `${base}/${name}`, sha256: sums[name] };
  };
  return {
    version, repo,
    homepage: `https://github.com/${repo}`,
    licenseUrl: `https://github.com/${repo}/blob/main/LICENSE`,
    notesUrl: `https://github.com/${repo}/releases/tag/v${version}`,
    win: asset('win-x64.exe'),
    macArm: asset('mac-arm64.dmg'),
    macIntel: asset('mac-x64.dmg'),
  };
}

/** The three winget manifest files of one version, { file name: text }. */
export function wingetManifests(c) {
  const id = 'Terrio.CodeLoupe';
  const head = type => `PackageIdentifier: ${id}\nPackageVersion: ${c.version}\n${type}`;
  const tail = type => `ManifestType: ${type}\nManifestVersion: ${WINGET_SCHEMA}\n`;
  return {
    [`${id}.yaml`]: `${head('DefaultLocale: en-US\n')}${tail('version')}`,
    [`${id}.installer.yaml`]: `${head('')}InstallerLocale: en-US
InstallerType: nullsoft
Scope: user
InstallModes:
- interactive
- silent
- silentWithProgress
InstallerSwitches:
  Silent: /S
  SilentWithProgress: /S
UpgradeBehavior: install
Installers:
- Architecture: x64
  InstallerUrl: ${c.win.url}
  InstallerSha256: ${c.win.sha256.toUpperCase()}
${tail('installer')}`,
    [`${id}.locale.en-US.yaml`]: `${head('PackageLocale: en-US\n')}Publisher: Terrio
PublisherUrl: https://github.com/${c.repo.split('/')[0]}
PackageName: CodeLoupe
PackageUrl: ${c.homepage}
License: ${LICENSE_ID}
LicenseUrl: ${c.licenseUrl}
Copyright: Terrio
ShortDescription: ${JSON.stringify(DESCRIPTION)}
Moniker: codeloupe
Tags:
- ai
- code-index
- developer-tools
- mcp
ReleaseNotesUrl: ${c.notesUrl}
${tail('defaultLocale')}`,
  };
}

/**
 * A Scoop manifest. The NSIS installer is not run: Scoop unpacks it as an archive (`#/dl.7z`), so the app lands in
 * the Scoop directory like a portable app and Scoop alone updates it (the app notices the missing uninstaller and does
 * not update itself). Which layout 7-Zip leaves depends on its version (the app as a nested app-64.7z, or unpacked
 * directly), so the pre_install script copes with both.
 */
export function scoopManifest(c) {
  const exe = `${c.win.url}#/dl.7z`;
  return {
    version: c.version,
    description: DESCRIPTION,
    homepage: c.homepage,
    license: { identifier: LICENSE_ID, url: c.licenseUrl },
    notes: 'CodeLoupe updates through Scoop (scoop update codeloupe); its own updater is off for this install.',
    architecture: { '64bit': { url: exe, hash: c.win.sha256 } },
    pre_install: [
      '$nested = Get-ChildItem "$dir\\`$PLUGINSDIR" -Filter app-64.7z -ErrorAction SilentlyContinue | Select-Object -First 1',
      'if ($nested) { Expand-7zipArchive $nested.FullName $dir }',
      'Remove-Item "$dir\\`$*", "$dir\\Uninstall*" -Recurse -Force -ErrorAction SilentlyContinue',
    ],
    bin: [['resources\\codeloupe\\bin\\codeloupe.bat', 'codeloupe']],
    shortcuts: [['CodeLoupe.exe', 'CodeLoupe']],
    checkver: 'github',
    autoupdate: {
      architecture: { '64bit': { url: `https://github.com/${c.repo}/releases/download/v$version/CodeLoupe-$version-win-x64.exe#/dl.7z` } },
      hash: { url: '$baseurl/SHA256SUMS.txt', regex: '$sha256\\s+\\*?CodeLoupe-$version-win-x64\\.exe' },
    },
  };
}

/** A Homebrew cask; the postflight removes the quarantine flag, so the unsigned app opens without the manual allow. */
export function caskManifest(c) {
  return `cask "codeloupe" do
  arch arm: "arm64", intel: "x64"

  version "${c.version}"
  sha256 arm:   "${c.macArm.sha256}",
         intel: "${c.macIntel.sha256}"

  url "https://github.com/${c.repo}/releases/download/v#{version}/CodeLoupe-#{version}-mac-#{arch}.dmg"
  name "CodeLoupe"
  desc "${CASK_DESCRIPTION}"
  homepage "${c.homepage}"

  livecheck do
    url :url
    strategy :github_latest
  end

  depends_on :macos

  app "CodeLoupe.app"
  binary "#{appdir}/CodeLoupe.app/Contents/Resources/codeloupe/bin/codeloupe"

  # The app is signed ad hoc, not notarised: without this Gatekeeper asks for a manual allow on the first start.
  postflight do
    system_command "/usr/bin/xattr",
                   args: ["-dr", "com.apple.quarantine", "#{appdir}/CodeLoupe.app"]
  end

  zap trash: [
    "~/Library/Application Support/codeloupe-desktop",
    "~/Library/Caches/codeloupe",
    "~/Library/Preferences/cz.terrio.codeloupe.plist",
    "~/Library/Saved Application State/cz.terrio.codeloupe.savedState",
  ]
end
`;
}

/** Reads the release files in `dir`, checks the installers present against their checksums and writes everything to `out`. */
export function generate({ dir, version, out, repo }) {
  const sumsFile = path.join(dir, 'SHA256SUMS.txt');
  if (!fs.existsSync(sumsFile)) throw new Error(`no SHA256SUMS.txt in ${dir}`);
  const sums = parseChecksums(fs.readFileSync(sumsFile, 'utf8'));
  const c = releaseContext({ version, repo, sums });
  for (const a of [c.win, c.macArm, c.macIntel]) {
    const file = path.join(dir, a.name);
    if (!fs.existsSync(file)) continue;
    const actual = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
    if (actual !== a.sha256) throw new Error(`${a.name}: SHA256SUMS.txt says ${a.sha256}, the file is ${actual}`);
  }

  const put = (rel, text) => {
    const file = path.join(out, rel);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text);
    return file;
  };
  const written = [];
  for (const [name, text] of Object.entries(wingetManifests(c))) written.push(put(`winget/manifests/t/Terrio/CodeLoupe/${version}/${name}`, text));
  written.push(put('scoop/codeloupe.json', JSON.stringify(scoopManifest(c), null, 2) + '\n'));
  written.push(put('homebrew/Casks/codeloupe.rb', caskManifest(c)));
  return written;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = name => (args.includes(`--${name}`) ? args[args.indexOf(`--${name}`) + 1] : undefined);
  const [dir, version, out] = [opt('dir'), opt('version'), opt('out')];
  if (!dir || !version || !out) {
    console.error('usage: packaging-manifests.mjs --dir <release files> --version <v> --out <dir> [--repo owner/name]');
    process.exit(2);
  }
  try {
    for (const file of generate({ dir, version, out, repo: opt('repo') })) console.log(`wrote ${path.relative(process.cwd(), file)}`);
  } catch (e) {
    console.error(e.message);
    process.exit(1);
  }
}
