import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { MockApi } from '../src/main/api/MockApi';
import { CommandTable } from '../src/renderer/src/components/CommandTable';
import { moneyText, repeatText, runLinks, sortOf } from '../src/renderer/src/commandModel';
import type { CommandGroup, CommandsView } from '../src/shared/commands';
import { validateRequest, type ApiRequest } from '../src/shared/request';

const api = new MockApi(Date.parse('2026-10-07T12:00:00Z'));
const get = async (req: ApiRequest): Promise<CommandsView> => {
  const v = validateRequest(req);
  if (!v.ok) throw new Error(v.error);
  return (await api.get(v.request)) as CommandsView;
};

const group: CommandGroup = {
  command: 'rg -n <str> <path>', kind: 'shell', calls: 120, runs: 14, resultTokens: 48_000, argTokens: 900, repeats: 7, repeatTokens: 3_400, errors: 2,
  weighted: 410_000, costPct: 12.4, money: 3.21, topRuns: [{ id: '1042', role: 'coder', ter: 'TER-5', title: 'Implement TER-5', calls: 30, weighted: 90_000 }],
};

describe('the commands request', () => {
  it('takes the period, role, top and sort, and nothing else', () => {
    const ok = validateRequest({ resource: 'commands', query: { since: '7d', role: 'coder', top: 30, sort: 'calls' } });
    expect(ok).toMatchObject({ ok: true, path: '/ui-api/v1/commands?since=7d&role=coder&top=30&sort=calls' });
    expect(validateRequest({ resource: 'commands', query: { range: '7d' } }).ok).toBe(false);
    expect(validateRequest({ resource: 'commands', id: 'x' }).ok).toBe(false);
  });
});

describe('the mock commands report', () => {
  it('ranks the groups, and every call is in exactly one group', async () => {
    const v = await get({ resource: 'commands', query: { since: '30d', top: 100 } });
    const r = v.report;
    expect(r.groups.length).toBe(r.groupCount);
    expect(r.groups.map(g => g.weighted)).toEqual([...r.groups.map(g => g.weighted)].sort((a, b) => b - a));
    expect(r.groups.reduce((a, g) => a + g.calls, 0)).toBe(r.calls);
    expect(r.categories.reduce((a, c) => a + c.calls, 0)).toBe(r.calls);
    expect(r.groups[0].topRuns.length).toBeGreaterThan(0);
    expect(v.roles).toContain('coder');
  });

  it('narrows to a role and ranks by calls on request', async () => {
    const all = await get({ resource: 'commands', query: { since: '30d' } });
    const coder = await get({ resource: 'commands', query: { since: '30d', role: 'coder' } });
    expect(coder.report.role).toBe('coder');
    expect(coder.report.runs).toBeLessThan(all.report.runs);
    expect(coder.report.roles.map(s => s.key)).toEqual(['coder']);
    const byCalls = await get({ resource: 'commands', query: { since: '30d', sort: 'calls' } });
    expect(byCalls.report.groups.map(g => g.calls)).toEqual([...byCalls.report.groups.map(g => g.calls)].sort((a, b) => b - a));
    expect((await get({ resource: 'commands', query: { since: '30d', top: 3 } })).report.groups).toHaveLength(3);
  });

  it('shorter periods hold fewer runs, and the weeks are ISO weeks', async () => {
    const day = await get({ resource: 'commands', query: { since: '24h' } });
    const month = await get({ resource: 'commands', query: { since: '30d' } });
    expect(day.report.runs).toBeLessThanOrEqual(month.report.runs);
    for (const w of month.report.weeks) expect(w.key).toMatch(/^\d{4}-W\d{2}$/);
  });
});

describe('the command table', () => {
  it('shows what a group cost and links to the runs that carried most of it', () => {
    const html = renderToStaticMarkup(<CommandTable label="Commands" groups={[group]} currency="USD" priced sort="cost" onSort={() => {}} onRun={() => {}} />);
    expect(html).toContain('rg -n &lt;str&gt; &lt;path&gt;');
    expect(html).toContain('12.4%');
    expect(html).toContain('7 · 3.4k');
    expect(html).toContain('Money (USD)');
    expect(html).toContain('3.21');
    expect(html).toContain('#1042');
    expect(html).toContain('aria-sort="descending"');
  });

  it('leaves the money and the run links out when there is nothing to show them from', () => {
    const html = renderToStaticMarkup(<CommandTable label="Commands" groups={[{ ...group, money: null }]} currency="USD" priced={false} />);
    expect(html).not.toContain('Money');
    expect(html).not.toContain('#1042');
  });

  it('says so when no tool call matched', () => {
    expect(renderToStaticMarkup(<CommandTable label="Commands" groups={[]} currency="USD" priced={false} />)).toContain('No tool calls in this period');
  });
});

describe('the command model', () => {
  it('names money, repeats and sort keys', () => {
    expect(moneyText(1.239, 'USD')).toBe('1.24 USD');
    expect(moneyText(null, 'USD')).toBe('—');
    expect(repeatText({ repeats: 0, repeatTokens: 0 })).toBe('—');
    expect(repeatText({ repeats: 12, repeatTokens: 3400 })).toBe('12 · 3.4k');
    expect(sortOf('tokens')).toBe('tokens');
    expect(sortOf('command')).toBeNull();
  });

  it('describes each run of a group for a link title', () => {
    expect(runLinks(group)).toEqual([{ id: '1042', text: '#1042', title: 'coder · TER-5 · 30 calls, 90k weighted · Implement TER-5' }]);
  });
});
