import { describe, expect, it } from 'vitest';
import { MockWorkspaces } from '../src/main/api/mockWorkspaces';
import { confirmable, dockerCounts, joinWorkspaces, matches, stateCounts } from '../src/renderer/src/workspaceModel';

const mock = new MockWorkspaces(Date.parse('2026-10-08T12:00:00Z'));
const rows = joinWorkspaces(mock.workspaces(true), mock.resources(), mock.reconcile(), mock.releases().items, mock.ports());
const row = (name: string) => rows.find(r => r.name === name)!;

describe('workspace model', () => {
  it('shows every state of the registry, and a workspace that left it but still has resources', () => {
    const withGone = joinWorkspaces(mock.workspaces(false), mock.resources(), mock.reconcile(), [], null);
    expect(stateCounts(rows)).toMatchObject({ active: 5, landed: 3, abandoned: 1, orphan: 1 });
    expect(stateCounts(withGone).gone).toBe(0);
    const resources = mock.resources();
    resources.resources.push({ ...resources.resources.find(r => r.workspace === 'TER-664')!, kind: 'volume', names: ['ter-77_data'], id: 'v1', workspace: 'TER-77', workspaceState: null });
    const gone = joinWorkspaces(mock.workspaces(false), resources, mock.reconcile(), [], null).find(r => r.name === 'TER-77')!;
    expect(gone.state).toBe('gone');
    expect(gone.ws).toBeNull();
    expect(gone.resources).toHaveLength(1);
  });

  it('joins resources, plan, release and ports by repository and workspace name', () => {
    const c = dockerCounts(row('TER-671'));
    expect(c).toEqual({ containers: 2, volumes: 1, images: 0, networks: 1, running: 2, memoryBytes: null });
    const measured = joinWorkspaces(mock.workspaces(false), mock.resources(true), mock.reconcile(), [], null).find(r => r.name === 'TER-671')!;
    expect(dockerCounts(measured).memoryBytes).toBeGreaterThan(100 * 1024 * 1024);
    expect(row('TER-671').ports.map(p => p.allocation.port)).toEqual([19001, 19002]);
    expect(row('TER-92').release?.pending).toBe(1);
    expect(row('TER-92').plan.every(e => e.released && e.verdict === 'auto')).toBe(true);
  });

  it('keeps the same name of two repositories apart', () => {
    expect(rows.filter(r => r.name === 'TerrioImporter' || r.name === 'CodeLoupe')).toHaveLength(2);
    expect(row('CL-43').resources.map(r => r.names[0])).toEqual(['cl-43-daemon-1']);
  });

  it('puts what waits for a yes on its workspace: abandoned, orphan and adopted ones, and the orphan directory', () => {
    expect(confirmable(row('TER-591')).map(e => e.name)).toEqual(['ter-591-postgres-1', 'ter-591_pgdata']);
    expect(confirmable(row('TER-420-old')).map(e => e.kind).sort()).toEqual(['directory', 'volume']);
    expect(confirmable(row('TER-672')).map(e => e.name)).toEqual([]);
    expect(confirmable(row('TER-664'))).toEqual([]);
  });

  it('filters by repository, state, release and text', () => {
    const f = (o: Partial<{ repo: string; state: '' | 'landed' | 'released' | 'orphan'; q: string }>) =>
      rows.filter(r => matches(r, { repo: '', state: '', q: '', ...o })).map(r => r.name);
    expect(f({ repo: 'CodeLoupe' })).toEqual(['CodeLoupe', 'CL-43', 'CL-56']);
    expect(f({ state: 'landed' })).toEqual(['TER-664', 'TER-92', 'CL-56']);
    expect(f({ state: 'released' })).toEqual(['TER-92']);
    expect(f({ q: 'rate-limit' })).toEqual(['TER-591']);
    expect(f({ state: 'orphan' })).toEqual(['TER-420-old']);
  });
});
