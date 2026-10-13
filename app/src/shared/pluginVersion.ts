/** How the installed Claude Code plugin relates to the plugin folder this app ships (CL-255). */
export type PluginVersionState =
  /** Same version, or one of them is not known. */
  | 'same'
  /** Installed from the app's folder at another version: the app has been updated since, `claude plugin update` brings the new one. */
  | 'differs'
  /** The installed plugin follows a repository commit (marketplace from GitHub), so it has no version to compare with the app's. */
  | 'tracks-repository';

/** Claude Code names a plugin without a version after the commit (12 hex characters), or `unknown` when there is no repository. */
const COMMIT_VERSION = /^[0-9a-f]{12}(-.*)?$|^unknown$/;

export function pluginVersionState(installed: string | null, bundled: string | null): PluginVersionState {
  if (!installed || !bundled) return 'same';
  if (COMMIT_VERSION.test(installed)) return 'tracks-repository';
  return installed === bundled ? 'same' : 'differs';
}

/** The two commands that bring an installed plugin to the version the app ships (the marketplace catalog first, then the plugin). */
export const PLUGIN_REFRESH_COMMAND = 'claude plugin marketplace update codeloupe && claude plugin update codeloupe@codeloupe';
