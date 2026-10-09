#!/usr/bin/env node
// Publishes docs/wiki to the repository's GitHub wiki (a separate git repository, <repo>.wiki.git). The sources in this
// repository are the truth: the wiki is a mirror, so a page that is not in docs/wiki is deleted there, and running the
// script again without a change pushes nothing.
//
//   node tools/publish-wiki.mjs [--source docs/wiki] [--remote <git url>] [--dry-run]
//
// The remote defaults to https://github.com/$GITHUB_REPOSITORY.wiki.git. In GitHub Actions the token comes from
// GITHUB_TOKEN (the job needs `contents: write`); it reaches git through environment variables only, never an argument,
// a URL or a log line. The script refuses to run when the source has no Home.md. A wiki that does not exist yet (the
// repository setting is off, or its first page was never created in the web UI) is reported and is not an error:
// exit 0 with a warning, so a push to main is not turned red by a one-time manual step.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const BOT = { name: 'github-actions[bot]', email: '41898282+github-actions[bot]@users.noreply.github.com' };
const MISSING = /repository .*(not found|does not exist)|does not appear to be a git repository/i;   // wording differs between git versions

export class PublishError extends Error {}

/** Every file below `dir`, as paths relative to it with forward slashes. A link is skipped: it could point at a file of the runner. */
export function listFiles(dir, base = dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).filter(e => !e.isSymbolicLink()).flatMap(e => {
    const full = path.join(dir, e.name);
    return e.isDirectory() ? listFiles(full, base) : [path.relative(base, full).split(path.sep).join('/')];
  });
}

/** The environment git runs in: no prompts, no CRLF conversion, and the token (if any) as an Authorization header. */
export function gitEnv(env, remote) {
  const config = [['core.autocrlf', 'false'], ['core.safecrlf', 'false']];
  const token = env.GITHUB_TOKEN;
  if (token && /^https:\/\/github\.com\//.test(remote)) {
    const basic = Buffer.from(`x-access-token:${token}`).toString('base64');
    config.push(['http.https://github.com/.extraheader', `AUTHORIZATION: basic ${basic}`]);
  }
  const out = { ...env, GIT_TERMINAL_PROMPT: '0', GIT_CONFIG_COUNT: String(config.length) };
  config.forEach(([k, v], i) => { out[`GIT_CONFIG_KEY_${i}`] = k; out[`GIT_CONFIG_VALUE_${i}`] = v; });
  Object.assign(out, {
    GIT_AUTHOR_NAME: BOT.name, GIT_AUTHOR_EMAIL: BOT.email, GIT_COMMITTER_NAME: BOT.name, GIT_COMMITTER_EMAIL: BOT.email,
  });
  return out;
}

/** `text` with the token and its Authorization-header form removed. */
export function redact(text, env) {
  const token = env.GITHUB_TOKEN;
  if (!token) return text;
  const basic = Buffer.from(`x-access-token:${token}`).toString('base64');
  return text.split(token).join('***').split(basic).join('***');
}

function git(args, { cwd, env, remoteEnv }) {
  try {
    return execFileSync('git', args, { cwd, env: remoteEnv, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  } catch (e) {
    const stderr = redact(String(e.stderr ?? e.message), env).trim();
    const error = new PublishError(`git ${args[0]} failed: ${stderr}`);
    error.stderr = stderr;
    throw error;
  }
}

/**
 * Mirrors `source` into the wiki repository at `remote`. Returns { status: 'published' | 'unchanged' | 'dry-run' |
 * 'missing-wiki', files, changed }; throws PublishError when the source is unusable or git fails.
 */
export function publishWiki({ source, remote, env = process.env, dryRun = false, log = () => {}, attempts = 3 }) {
  if (!fs.existsSync(path.join(source, 'Home.md'))) throw new PublishError(`${source} has no Home.md: refusing to publish`);
  if (!remote) throw new PublishError('no remote: set GITHUB_REPOSITORY or pass --remote');
  const files = listFiles(source).sort();
  const remoteEnv = gitEnv(env, remote);
  const sha = (env.GITHUB_SHA ?? '').slice(0, 7);

  for (let attempt = 1; attempt <= attempts; attempt++) {
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'wiki-publish-'));
    try {
      try {
        git(['clone', '--quiet', remote, work], { env, remoteEnv });
      } catch (e) {
        if (MISSING.test(e.stderr ?? '')) {
          log('The wiki repository does not exist yet. Enable the wiki in the repository settings and create its first page once ' +
            'in the web UI (Wiki, "Create the first page"); the next publish then fills it.');
          return { status: 'missing-wiki', files: files.length, changed: false };
        }
        throw e;
      }
      const opts = { cwd: work, env, remoteEnv };
      let branch = 'master';
      try {
        git(['rev-parse', '--verify', '--quiet', 'HEAD'], opts);
        branch = git(['symbolic-ref', '--short', 'HEAD'], opts);
      } catch {
        git(['symbolic-ref', 'HEAD', 'refs/heads/master'], opts);   // an empty wiki repository: GitHub's wiki branch is master
      }

      for (const entry of fs.readdirSync(work)) if (entry !== '.git') fs.rmSync(path.join(work, entry), { recursive: true, force: true });
      for (const file of files) {
        const target = path.join(work, file);
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.copyFileSync(path.join(source, file), target);
      }
      git(['add', '--all'], opts);
      const changes = git(['status', '--porcelain'], opts).split('\n').filter(Boolean);
      if (changes.length === 0) {
        log(`The wiki is up to date (${files.length} files).`);
        return { status: 'unchanged', files: files.length, changed: false };
      }
      if (dryRun) {
        log(`Would publish ${changes.length} changed files of ${files.length}:\n${changes.join('\n')}`);
        return { status: 'dry-run', files: files.length, changed: true };
      }
      git(['commit', '--quiet', '-m', sha ? `Publish docs/wiki from ${sha}` : 'Publish docs/wiki'], opts);
      try {
        git(['push', '--quiet', 'origin', `HEAD:refs/heads/${branch}`], opts);
      } catch (e) {
        if (attempt < attempts && /rejected|fetch first|non-fast-forward/i.test(e.stderr ?? '')) { log('The wiki moved meanwhile; trying again.'); continue; }
        throw e;
      }
      log(`Published ${changes.length} changed files of ${files.length} to the wiki.`);
      return { status: 'published', files: files.length, changed: true };
    } finally {
      fs.rmSync(work, { recursive: true, force: true });
    }
  }
  throw new PublishError('the wiki kept moving; gave up');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = name => (args.includes(`--${name}`) ? args[args.indexOf(`--${name}`) + 1] : undefined);
  const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
  const source = path.resolve(opt('source') ?? path.join(root, 'docs', 'wiki'));
  const remote = opt('remote') ?? (process.env.GITHUB_REPOSITORY ? `https://github.com/${process.env.GITHUB_REPOSITORY}.wiki.git` : undefined);
  try {
    const result = publishWiki({ source, remote, dryRun: args.includes('--dry-run'), log: m => console.log(m) });
    if (result.status === 'missing-wiki' && process.env.GITHUB_ACTIONS) {
      console.log('::warning title=Wiki repository missing::Create the wiki\'s first page once in the web UI, then re-run this workflow.');
    }
  } catch (e) {
    console.error(redact(e instanceof PublishError ? e.message : String(e.stack ?? e), process.env));
    process.exit(1);
  }
}
