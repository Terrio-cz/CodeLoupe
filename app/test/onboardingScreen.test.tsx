import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Onboarding } from '../src/renderer/src/screens/Onboarding';

describe('Onboarding', () => {
  it('shows the four steps, lets the user leave at once and offers to skip each step', () => {
    const html = renderToStaticMarkup(<Onboarding onFinish={() => undefined} />);
    for (const title of ['Repozitáře', 'YouTrack', 'Claude Code', 'Zkouška']) expect(html).toContain(title);
    expect(html).toContain('Přeskočit vše');
    expect(html).toContain('Přeskočit krok');
    expect(html).toContain('aria-current="step"');
    expect(html).toContain('Přidat repozitáře…');
    expect(html).not.toContain('type="password"');
  });
});
