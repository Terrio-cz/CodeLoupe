import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { DaemonVersionBanner } from '../src/renderer/src/components/DaemonVersionBanner';
import type { DaemonState } from '../src/shared/ipc';

/** The empty screens of CL-260 must carry the reason and the way out, on every screen. */
describe('DaemonVersionBanner', () => {
  const state = (over: Partial<DaemonState>): DaemonState => ({
    phase: 'error', status: null, port: 47392, message: null, versionMismatch: null, manualStop: false, checkedAt: null, ...over,
  });

  it('names both versions, the jar and the restart', () => {
    const html = renderToStaticMarkup(<DaemonVersionBanner daemon={state({
      versionMismatch: { running: '0.1.0', expected: '0.1.9', jar: 'C:\\deploys\\app2\\lib\\codeloupe-0.1.0.jar' },
    })} />);
    expect(html).toContain('0.1.0');
    expect(html).toContain('0.1.9');
    expect(html).toContain('47392');
    expect(html).toContain('codeloupe-0.1.0.jar');
    expect(html).toContain('Restart daemon');
  });

  it('shows why a restart was refused', () => {
    const html = renderToStaticMarkup(<DaemonVersionBanner daemon={state({
      versionMismatch: { running: '0.1.0', expected: '0.1.9', jar: null },
      message: 'Start failed: it runs 2 job(s) that replacing it would end',
    })} />);
    expect(html).toContain('it runs 2 job(s)');
  });

  it('is silent while the daemon is the one the app ships', () => {
    expect(renderToStaticMarkup(<DaemonVersionBanner daemon={state({ phase: 'running' })} />)).toBe('');
    expect(renderToStaticMarkup(<DaemonVersionBanner daemon={null} />)).toBe('');
  });
});
