import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { GapReportRefresh, gapsArgs } from '../src/main/gaps/GapReportRefresh';
import { DEFAULT_SETTINGS } from '../src/shared/settings';

describe('gap report refresh', () => {
  it('builds a fixed argument list: the CLI arguments, the verb, the last 30 days and the report file', () => {
    const args = gapsArgs(['-cp', 'lib/*', 'codeloupe.MainKt'], '/h/gaps-report.json', new Date('2026-10-08T12:00:00Z'));
    expect(args).toEqual(['-cp', 'lib/*', 'codeloupe.MainKt', 'metrics', 'gaps', '--since', '2026-09-08', '--out', '/h/gaps-report.json']);
  });

  it('runs the CLI once for requests that arrive together, in the daemon home', async () => {
    const calls: { command: string; args: string[]; home: string | undefined }[] = [];
    let release!: () => void;
    const gate = new Promise<void>(r => { release = r; });
    const refresh = new GapReportRefresh(() => ({ ...DEFAULT_SETTINGS, cliCommand: 'C:/x/codeloupe.exe' }), () => '/h', async (command, args, env) => {
      calls.push({ command, args, home: env.CODELOUPE_HOME });
      await gate;
    });
    const a = refresh.run();
    const b = refresh.run();
    release();
    expect(await a).toEqual({ ok: true, message: 'Report přepočítán.' });
    expect(await b).toEqual(await a);
    expect(calls).toHaveLength(1);
    expect(calls[0].home).toBe('/h');
    expect(calls[0].args.at(-1)).toBe(path.join('/h', 'gaps-report.json'));
    await refresh.run();
    expect(calls).toHaveLength(2);
  });

  it('turns a failed run into a message, not an exception', async () => {
    const refresh = new GapReportRefresh(() => ({ ...DEFAULT_SETTINGS, cliCommand: 'C:/x/codeloupe.exe' }), () => '/h', () => Promise.reject(new Error('exit 1')));
    expect(await refresh.run()).toEqual({ ok: false, message: 'Report se nepodařilo přepočítat: exit 1' });
  });
});
