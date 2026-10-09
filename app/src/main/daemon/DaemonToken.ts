import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

export const TOKEN_HEADER = 'x-codeloupe-token';
export const NONCE_HEADER = 'x-codeloupe-nonce';
export const PROOF_HEADER = 'x-codeloupe-proof';
const TOKEN = /^[A-Za-z0-9_-]{32,128}$/;

/** The token in `<dir>/daemon.token` (the header line `x-codeloupe-token: <value>`, or the bare value); null when there is none or it is unusable. */
export function readToken(dir: string): string | null {
  try {
    const value = fs.readFileSync(path.join(dir, 'daemon.token'), 'utf8').trim().split(/\s+/).pop() ?? '';
    return TOKEN.test(value) ? value : null;
  } catch {
    return null;
  }
}

/** What a daemon that holds `token` answers to `nonce`; the same text as DaemonToken.proof in the daemon and `daemon-auth.sh`. */
export function proofOf(token: string, nonce: string): string {
  return crypto.createHash('sha256').update(`codeloupe-proof:${token}:${nonce}`).digest('hex');
}

export function newNonce(): string {
  return crypto.randomBytes(16).toString('hex');
}
