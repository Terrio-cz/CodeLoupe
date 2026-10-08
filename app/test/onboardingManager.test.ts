import { describe, expect, it } from 'vitest';
import { OnboardingManager, type OnboardingDeps } from '../src/main/onboarding/OnboardingManager';
import type { CliResult } from '../src/main/env/EnvManager';
import { DEFAULT_SETTINGS, sanitizeSettings } from '../src/shared/settings';

interface Call { args: string[]; stdin: string | null }

function setup(over: Partial<OnboardingDeps> = {}, reply: (c: Call) => CliResult = () => ({ code: 0, stdout: '', stderr: '' })) {
  const calls: Call[] = [];
  const deps: OnboardingDeps = {
    run: async (args, stdin) => { const c = { args, stdin }; calls.push(c); return reply(c); },
    pickFolders: async () => ['C:/Work/App', 'C:/Work/Lib'],
    repos: async () => [{ id: 'app-1a2b', path: 'C:/Work/App' }],
    ...over,
  };
  return { manager: new OnboardingManager(deps), calls };
}

const report = (o: object) => ({ added: [], already: [], rejected: [], indexing: [], ...o });

describe('adding repositories', () => {
  it('has the CLI write them and says what happened, with the folders from the native dialog only', async () => {
    const t = setup({}, () => ({ code: 0, stdout: JSON.stringify(report({ added: ['C:/Work/App'], already: ['C:/Work/Lib'], rejected: [{ path: 'C:/x', reason: 'not a git repository (no .git)' }] })), stderr: '' }));
    const out = await t.manager.addRepositories();
    expect(out).toMatchObject({ ok: true, added: ['C:/Work/App'], already: ['C:/Work/Lib'] });
    expect((out as { message: string }).message).toBe('Added 1, already present 1, rejected 1. The index is building in the background.');
    expect(t.calls[0].args).toEqual(['repos', 'add', '--json', 'C:/Work/App', 'C:/Work/Lib']);
  });

  it('does nothing when the dialog was cancelled, in mock mode and for too many or odd folders', async () => {
    const none = setup({ pickFolders: async () => [] });
    expect(await none.manager.addRepositories()).toBe('cancelled');
    expect(none.calls).toHaveLength(0);
    const mock = setup({ blocked: () => 'The data source is Mock' });
    expect(await mock.manager.addRepositories()).toMatchObject({ ok: false, message: 'The data source is Mock' });
    expect(mock.calls).toHaveLength(0);
    const odd = setup({ pickFolders: async () => ['C:/ok', 'C:/a\nb', ''] });
    await odd.manager.addRepositories();
    expect(odd.calls[0].args).toEqual(['repos', 'add', '--json', 'C:/ok']);
    const many = setup({ pickFolders: async () => Array.from({ length: 51 }, (_, i) => `C:/r${i}`) });
    expect(await many.manager.addRepositories()).toMatchObject({ ok: false });
    expect(many.calls).toHaveLength(0);
  });

  it('reports no repository, a CLI that failed and one that could not start, without throwing', async () => {
    const rejected = setup({}, () => ({ code: 1, stdout: JSON.stringify(report({ rejected: [{ path: 'C:/x', reason: 'not a folder' }] })), stderr: '' }));
    expect(await rejected.manager.addRepositories()).toMatchObject({ ok: false, message: 'None of the selected folders is a git repository.' });
    const broken = setup({}, () => ({ code: 2, stdout: '', stderr: 'Error: config.json is not valid JSON; fix it by hand first\n' }));
    expect(await broken.manager.addRepositories()).toMatchObject({ ok: false, message: expect.stringContaining('config.json is not valid JSON') });
    const missing = setup({ run: async () => { throw new Error('command “codeloupe” was not found'); } });
    expect(await missing.manager.addRepositories()).toMatchObject({ ok: false, message: expect.stringContaining('was not found') });
  });
});

describe('the trial query', () => {
  it('asks the CLI for the repository map on the path of a repository the daemon knows, never on a path from the page', async () => {
    const t = setup({}, () => ({ code: 0, stdout: 'class App  src/App.kt:3\n', stderr: '' }));
    expect(await t.manager.query('app-1a2b')).toEqual({ ok: true, text: 'class App  src/App.kt:3' });
    expect(t.calls[0].args).toEqual(['outline', '--root', 'C:/Work/App', '--budget', '600']);
    expect(await t.manager.query('nobody')).toMatchObject({ ok: false });
    expect(await t.manager.query('../../etc')).toMatchObject({ ok: false, text: 'Invalid repository ID.' });
    expect(t.calls).toHaveLength(1);
  });

  it('passes on a failing answer and a busy index as text', async () => {
    const t = setup({}, () => ({ code: 1, stdout: 'busy: the index of this repository is still building', stderr: '' }));
    expect(await t.manager.query('app-1a2b')).toEqual({ ok: false, text: 'busy: the index of this repository is still building' });
    const mock = setup({ blocked: () => 'The data source is Mock' });
    expect(await mock.manager.query('app-1a2b')).toEqual({ ok: false, text: 'The data source is Mock' });
  });
});

describe('the first-run flag', () => {
  it('is off by default and kept when the renderer sends it', () => {
    expect(DEFAULT_SETTINGS.onboardingDone).toBe(false);
    expect(sanitizeSettings({ onboardingDone: true }).onboardingDone).toBe(true);
    expect(sanitizeSettings({ onboardingDone: 'yes' }).onboardingDone).toBe(false);
  });
});
