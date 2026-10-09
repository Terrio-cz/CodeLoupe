import { createPublicKey, verify, type KeyObject } from 'node:crypto';

export type FeedVerdict = { ok: true } | { ok: false; reason: string };

const SIGNATURE_BYTES = 64;

/**
 * Whether `signature` (base64 of a raw Ed25519 signature, or null when the release has none) is a signature of `feed` by one
 * of `keys` (base64 DER SubjectPublicKeyInfo). A key that cannot be read never accepts anything.
 */
export function verifyFeedSignature(feed: string, signature: string | null, keys: readonly string[]): FeedVerdict {
  if (signature === null || signature.trim() === '') return { ok: false, reason: 'the release has no signature for its update feed' };
  const raw = Buffer.from(signature.trim(), 'base64');
  if (raw.length !== SIGNATURE_BYTES) return { ok: false, reason: 'the signature of the update feed is malformed' };
  const data = Buffer.from(feed, 'utf8');
  for (const key of keys.map(publicKey)) {
    if (key && verify(null, data, key, raw)) return { ok: true };
  }
  return { ok: false, reason: 'the update feed is not signed by a key this version trusts' };
}

function publicKey(base64: string): KeyObject | null {
  try {
    return createPublicKey({ key: Buffer.from(base64, 'base64'), format: 'der', type: 'spki' });
  } catch {
    return null;
  }
}
