import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Onboarding } from '../src/renderer/src/screens/Onboarding';

describe('Onboarding', () => {
  it('shows the four steps, lets the user leave at once and offers to skip each step', () => {
    const html = renderToStaticMarkup(<Onboarding onFinish={() => undefined} />);
    for (const title of ['Repositories', 'YouTrack', 'Claude Code', 'Test']) expect(html).toContain(title);
    expect(html).toContain('Skip all');
    expect(html).toContain('Skip step');
    expect(html).toContain('aria-current="step"');
    expect(html).toContain('Add repositories…');
    expect(html).not.toContain('type="password"');
  });
});
