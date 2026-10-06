// Thin git helpers. Everything goes through the git CLI so any repository layout (worktrees,
// submodules, sparse checkouts) behaves as git itself sees it.
import { spawn, spawnSync } from 'node:child_process';

export function git(cwd, args, { input, allowFail = false } = {}) {
  const r = spawnSync('git', ['-C', cwd, ...args], { encoding: 'utf8', input, maxBuffer: 256 * 1024 * 1024, windowsHide: true });
  if (r.status !== 0 && !allowFail) throw new Error(`git ${args.join(' ')}: ${(r.stderr || '').trim().split('\n').pop()}`);
  return r.status === 0 ? r.stdout : null;
}

// [{ mode, type, sha, size, path }] of every blob in a commit.
export function lsTree(cwd, commit) {
  const out = git(cwd, ['ls-tree', '-r', '-z', '--long', '--full-tree', commit]);
  return out.split('\0').filter(Boolean).map(line => {
    const tab = line.indexOf('\t'); const [mode, type, sha, size] = line.slice(0, tab).trim().split(/\s+/);
    return { mode, type, sha, size: Number(size), path: line.slice(tab + 1) };
  }).filter(e => e.type === 'blob');
}

// Streams blob contents via one `git cat-file --batch`; calls onBlob(sha, text) in request order.
export function readBlobs(cwd, shas, onBlob) {
  return new Promise((resolve, reject) => {
    const p = spawn('git', ['-C', cwd, 'cat-file', '--batch'], { windowsHide: true });
    let buf = Buffer.alloc(0); let i = 0; let failed = null; let chain = Promise.resolve();
    // One blob at a time with the pipe paused: memory stays bounded however fast git writes.
    const drain = async () => {
      while (true) {
        const nl = buf.indexOf(10); if (nl < 0) return;
        const header = buf.subarray(0, nl).toString(); const [sha, type, size] = header.split(' ');
        if (type === 'missing') { buf = buf.subarray(nl + 1); i++; continue; }
        const n = Number(size); if (buf.length < nl + 1 + n + 1) return;
        const text = buf.subarray(nl + 1, nl + 1 + n).toString('utf8'); buf = buf.subarray(nl + 1 + n + 1); i++;
        await onBlob(sha, text);
      }
    };
    p.stdout.on('data', d => {
      buf = buf.length ? Buffer.concat([buf, d]) : d;
      p.stdout.pause();
      chain = chain.then(drain).catch(e => { failed ??= e; }).finally(() => p.stdout.resume());
    });
    p.on('error', reject);
    p.on('close', code => { chain.then(drain).then(() => failed ? reject(failed) : code === 0 ? resolve(i) : reject(new Error(`git cat-file exit ${code}`)), reject); });
    p.stdin.end(shas.join('\n') + '\n');
  });
}
