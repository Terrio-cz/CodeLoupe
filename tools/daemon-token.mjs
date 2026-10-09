// The header line a client sends to a daemon that wants its token (CL-158): `<home>/daemon.token` holds it as `x-codeloupe-token: <value>`.
// Empty when the home has no token yet (a daemon from before it), so the same call works against both.
import fs from 'node:fs';
import path from 'node:path';

export function daemonTokenHeader(home) {
  try {
    const [name, value] = fs.readFileSync(path.join(home, 'daemon.token'), 'utf8').trim().split(/:\s*/);
    return value ? { [name]: value } : {};
  } catch { return {}; }
}
