/**
 * Public keys that sign the update feeds (`latest.yml`, `latest-linux.yml`), as base64 of the DER SubjectPublicKeyInfo of an
 * Ed25519 key (`openssl pkey -in update-signing.pem -pubout -outform DER | base64 -w0`). More than one key is accepted so that
 * a new key can ship in one release before the old one is dropped from the next.
 *
 * Empty until the owner has made the key pair and stored the private half as the `UPDATE_SIGNING_KEY` secret of the `release`
 * environment: a build without a key installs what the release holds, checked against the SHA-512 of the feed as before,
 * and says so in the update log. Once a key is listed, a feed without a valid signature by one of them is refused.
 */
export const UPDATE_PUBLIC_KEYS: readonly string[] = ['MCowBQYDK2VwAyEAQTGxSZWKoNskGPTcXPEV9z1AST2NKQsxMx3aKHHjjmE='];
