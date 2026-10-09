// electron-builder `afterPack` hook (CL-161, CL-130): flips the Electron fuses, then signs the macOS app ad hoc.
//
// The order is the point. Flipping a fuse rewrites the Electron binary, which breaks any signature on it, so the fuses go first and
// the ad hoc signature (ad-hoc-sign.mjs) seals the result. `electronFuses` in electron-builder.yml would flip them after this hook,
// i.e. after the signature, and leave macOS with a bundle whose signature is invalid.
//
// The packaged app is not a general Node.js: ELECTRON_RUN_AS_NODE, NODE_OPTIONS and --inspect would let anything that can set the
// environment of a launch run its own script as the app. The app uses none of them; its child processes are the daemon's own runtime
// and the claude CLI, started with execFile.
import { FuseV1Options, FuseVersion } from '@electron/fuses';
import { afterPack as signAdHoc } from './ad-hoc-sign.mjs';

export const FUSES = {
  version: FuseVersion.V1,
  [FuseV1Options.RunAsNode]: false,
  [FuseV1Options.EnableNodeOptionsEnvironmentVariable]: false,
  [FuseV1Options.EnableNodeCliInspectArguments]: false,
};

export async function afterPack(context) {
  await context.packager.addElectronFuses(context, FUSES);
  await signAdHoc(context);
}
