import path from 'node:path';

/** How an installation gets a newer version: by itself, by telling the user, or not at all. */
export type UpdateMode =
  | { kind: 'install'; engine: 'nsis' | 'appimage' }
  | { kind: 'notify'; reason: string }
  | { kind: 'unavailable'; reason: string };

export interface ModeEnv {
  platform: NodeJS.Platform;
  packaged: boolean;
  /** Set inside an AppImage. */
  appImage?: string;
  execPath: string;
  exists(file: string): boolean;
}

/**
 * Windows (NSIS) and Linux (AppImage) update themselves from the release feed. macOS has no Developer ID signature
 * (nothing is paid for, docs/code-signing.md), which Squirrel.Mac needs, so it only says that a release exists. So does
 * a .deb (the package manager owns the files) and a Windows copy that was unpacked by Scoop: it has no uninstaller, and
 * Scoop alone updates it.
 */
export function detectMode(env: ModeEnv): UpdateMode {
  if (!env.packaged) return { kind: 'unavailable', reason: 'A development build does not update.' };
  if (env.platform === 'win32') {
    const uninstaller = path.win32.join(path.win32.dirname(env.execPath), `Uninstall ${path.win32.basename(env.execPath)}`);
    return env.exists(uninstaller)
      ? { kind: 'install', engine: 'nsis' }
      : { kind: 'notify', reason: 'A copy without an installer (from Scoop, for example): update it with the same package manager.' };
  }
  if (env.platform === 'darwin') return { kind: 'notify', reason: 'macOS without a Developer ID signature: the app only announces a new version; download it manually or through Homebrew.' };
  return env.appImage
    ? { kind: 'install', engine: 'appimage' }
    : { kind: 'notify', reason: '.deb package: update it through the package manager or by downloading the new version.' };
}
