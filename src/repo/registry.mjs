// Repositories the daemon knows: any path inside a git repository or one of its worktrees maps to one
// repository (keyed by its git common dir) with one base index of its default branch.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { git } from '../git.mjs';
import { View } from '../query/view.mjs';

const BUILD_SCRIPT = fileURLToPath(new URL('../index/build.mjs', import.meta.url));
const LOCATE_TTL_MS = 60_000;
const HEAD_TTL_MS = 2_000;

export class BusyError extends Error {}

const norm = p => path.resolve(p).replace(/\\/g, '/');

export class Registry {
  constructor(cfg, queue, log = () => {}) {
    this.cfg = cfg; this.queue = queue; this.log = log;
    this.repos = new Map(); this.located = new Map();
  }

  locate(root) {
    const key = norm(root);
    const hit = this.located.get(key);
    if (hit && Date.now() - hit.at < LOCATE_TTL_MS) return hit;
    if (!fs.existsSync(key)) throw new Error(`root does not exist: ${root}`);
    const out = git(key, ['rev-parse', '--path-format=absolute', '--show-toplevel', '--git-common-dir'], { allowFail: true });
    if (!out) throw new Error(`not inside a git repository: ${root}`);
    const [worktree, commonDir] = out.trim().split('\n').map(norm);
    const loc = { worktree, commonDir, at: Date.now() };
    this.located.set(key, loc);
    return loc;
  }

  repo(commonDir) {
    if (this.repos.has(commonDir)) return this.repos.get(commonDir);
    const id = crypto.createHash('sha1').update(commonDir.toLowerCase()).digest('hex').slice(0, 12);
    const dir = path.join(this.cfg.home, 'repos', id);
    fs.mkdirSync(dir, { recursive: true });
    let saved = {};
    try { saved = JSON.parse(fs.readFileSync(path.join(dir, 'repo.json'), 'utf8')); } catch {}
    const r = { id, dir, commonDir, baseCommit: null, baseFile: null, headAt: 0, head: null, ...saved, defaultRef: null };
    if (r.baseFile && !fs.existsSync(r.baseFile)) { r.baseCommit = null; r.baseFile = null; }
    r.defaultRef = this.defaultRef(r);
    this.repos.set(commonDir, r);
    return r;
  }

  // Configured base branch (.codeloupe.json "baseBranch" in the main worktree), else origin/HEAD, else main/master.
  defaultRef(r) {
    const mainWorktree = r.commonDir.endsWith('/.git') ? r.commonDir.slice(0, -5) : null;
    try {
      const cfg = JSON.parse(fs.readFileSync(path.join(mainWorktree || r.commonDir, '.codeloupe.json'), 'utf8'));
      if (cfg.baseBranch) return cfg.baseBranch;
    } catch {}
    const head = git(r.commonDir, ['symbolic-ref', '-q', '--short', 'refs/remotes/origin/HEAD'], { allowFail: true })?.trim();
    if (head) return head;
    for (const ref of ['origin/main', 'origin/master', 'main', 'master'])
      if (git(r.commonDir, ['rev-parse', '-q', '--verify', ref + '^{commit}'], { allowFail: true })) return ref;
    return 'HEAD';
  }

  headOf(r) {
    if (Date.now() - r.headAt > HEAD_TTL_MS) { r.head = git(r.commonDir, ['rev-parse', r.defaultRef + '^{commit}']).trim(); r.headAt = Date.now(); }
    return r.head;
  }

  save(r) {
    const { id, dir, commonDir, defaultRef, baseCommit, baseFile, lastBuild } = r;
    fs.writeFileSync(path.join(dir, 'repo.json'), JSON.stringify({ id, commonDir, defaultRef, baseCommit, baseFile, lastBuild }, null, 1));
  }

  // Base index for the repository's current default-branch commit. A stale base keeps answering while
  // the new one builds; only a missing base makes the caller wait (bounded by queryTimeoutMs).
  async base(r) {
    const head = this.headOf(r);
    if (r.baseCommit === head) return r;
    const job = this.queue.run('heavy', `build:${r.id}:${head}`, () => this.build(r, head));
    job.catch(e => this.log(`build ${r.id} ${head.slice(0, 7)} failed: ${e.message}`));
    if (r.baseFile) return r;
    let timer;
    const timeout = new Promise((_, rej) => { timer = setTimeout(() => rej(new BusyError(`indexing ${r.commonDir} (first build); retry in a few seconds`)), this.cfg.queryTimeoutMs); });
    try { await Promise.race([job, timeout]); } finally { clearTimeout(timer); }
    return r;
  }

  build(r, commit) {
    const tmp = path.join(r.dir, `base-${commit.slice(0, 12)}.tmp.db`);
    const out = path.join(r.dir, `base-${commit.slice(0, 12)}.db`);
    return new Promise((resolve, reject) => {
      const p = spawn(process.execPath, [`--max-old-space-size=${this.cfg.buildHeapMb}`, BUILD_SCRIPT, r.commonDir, commit, tmp],
        { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
      let stdout = '', stderr = '';
      const kill = setTimeout(() => p.kill(), this.cfg.buildTimeoutMs);
      p.stdout.on('data', d => { stdout += d; });
      p.stderr.on('data', d => { stderr += d; if (stderr.length > 8000) stderr = stderr.slice(-4000); });
      p.on('error', reject);
      p.on('close', code => {
        clearTimeout(kill);
        let res = null; try { res = JSON.parse(stdout.trim().split('\n').pop()); } catch {}
        if (code !== 0 || !res?.ok) return reject(new Error(res?.error || `build exited ${code}: ${stderr.trim().split('\n').pop() || ''}`));
        for (const f of [out, out + '-wal', out + '-shm']) fs.rmSync(f, { force: true });
        fs.renameSync(tmp, out);
        const old = r.baseFile;
        Object.assign(r, { baseCommit: commit, baseFile: out, lastBuild: { at: new Date().toISOString(), ...res } });
        this.save(r);
        this.log(`build ${r.id} ${commit.slice(0, 7)}: ${res.files} files in ${res.ms} ms, peak ${res.peakRssMb} MB`);
        this.prune(r, old);
        resolve(res);
      });
    });
  }

  // Old bases may still be open by an in-flight query (Windows refuses to delete open files): best effort.
  prune(r) {
    for (const f of fs.readdirSync(r.dir)) {
      const full = path.join(r.dir, f);
      if (!f.startsWith('base-') || full === r.baseFile || full.startsWith(r.baseFile + '-')) continue;
      try { fs.rmSync(full, { force: true }); } catch {}
    }
  }

  async view(root) {
    const loc = this.locate(root);
    const r = await this.base(this.repo(loc.commonDir));
    const view = new View(r.baseFile);
    const wtHead = git(loc.worktree, ['rev-parse', 'HEAD'], { allowFail: true })?.trim();
    const note = wtHead && wtHead !== r.baseCommit
      ? `(index of ${r.defaultRef}@${r.baseCommit.slice(0, 7)}; this worktree is at ${wtHead.slice(0, 7)} — its own changes are not indexed yet)`
      : null;
    return { view, repo: r, worktree: loc.worktree, note };
  }

  snapshot() {
    return [...this.repos.values()].map(r => ({ id: r.id, commonDir: r.commonDir, defaultRef: r.defaultRef, baseCommit: r.baseCommit, lastBuild: r.lastBuild }));
  }
}
