// Stamps the app's version into the Claude Code plugin the installer ships (CL-255).
//
// The plugin in the repository carries no version on purpose: Claude Code then versions a marketplace install from GitHub by
// the commit, so every change on main is an update. A pinned version there would keep a GitHub install on the cached copy
// for good (the plugin said 0.1.1 through ten releases). The copy inside the installer is another matter: the app installs it
// from its own folder, so it gets the version of the app that holds it, and `claude plugin update` finds a newer one after
// the app is updated. The stamp is applied by the afterPack hook, with the version electron-builder itself uses
// (`-c.extraMetadata.version` of a release, the package.json version of a development build).
import fs from 'node:fs';
import path from 'node:path';

const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const writeJson = (file, value) => fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);

/**
 * Sets `version` in the marketplace entry of every plugin of a marketplace folder (when the entry carries one) and in the
 * plugin.json of each plugin. Returns the files written.
 */
export function stampPluginVersion(marketplaceDir, version) {
  if (!version) throw new Error('no version to stamp into the plugin');
  const marketplaceFile = path.join(marketplaceDir, '.claude-plugin', 'marketplace.json');
  const marketplace = readJson(marketplaceFile);
  const written = [];
  for (const entry of marketplace.plugins ?? []) {
    const manifestFile = path.join(marketplaceDir, entry.source, '.claude-plugin', 'plugin.json');
    const manifest = readJson(manifestFile);
    writeJson(manifestFile, { ...manifest, version });
    written.push(manifestFile);
    if ('version' in entry) {
      entry.version = version;
      writeJson(marketplaceFile, marketplace);
      written.push(marketplaceFile);
    }
  }
  return written;
}

/** The version in every plugin.json (and every versioned marketplace entry) of a marketplace folder: `{ file, version }`, undefined when unset. */
export function shippedPluginVersions(marketplaceDir) {
  const marketplaceFile = path.join(marketplaceDir, '.claude-plugin', 'marketplace.json');
  const entries = readJson(marketplaceFile).plugins ?? [];
  if (!entries.length) throw new Error(`${marketplaceFile} lists no plugin`);
  const found = [];
  for (const entry of entries) {
    const file = path.join(marketplaceDir, entry.source, '.claude-plugin', 'plugin.json');
    found.push({ file, version: readJson(file).version });
    if ('version' in entry) found.push({ file: marketplaceFile, version: entry.version });
  }
  return found;
}

/** The Resources folder of an unpacked electron-builder app (where `extraResources` land). */
export function resourcesDirOf(context) {
  const { appOutDir, electronPlatformName, packager } = context;
  return electronPlatformName === 'darwin'
    ? path.join(appOutDir, `${packager.appInfo.productFilename}.app`, 'Contents', 'Resources')
    : path.join(appOutDir, 'resources');
}

/** electron-builder `afterPack` step: the plugin folder next to the app gets the app's version. */
export function stampPackedPlugin(context) {
  const dir = path.join(resourcesDirOf(context), 'claude-plugin');
  const written = stampPluginVersion(dir, context.packager.appInfo.version);
  console.log(`  • stamped plugin version ${context.packager.appInfo.version} into ${written.length} file(s) of ${dir}`);
}
