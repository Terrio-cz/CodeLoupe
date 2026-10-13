import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { MockJobs } from '../src/main/api/mockJobs';
import { SlotList } from '../src/renderer/src/screens/Jobs';

const now = Date.parse('2026-10-08T12:00:00Z');
const mock = new MockJobs(now);
const html = () => renderToStaticMarkup(
  <SlotList slots={mock.status({}).jobs!.slots} byId={new Map(mock.jobs.map(j => [j.id, j]))} now={now} onChanged={() => undefined} />,
);

describe('slot list', () => {
  it('shows capacity, where it comes from, holders and the queue with wait times', () => {
    const page = html();
    expect(page).toContain('gradle-test');
    expect(page).toContain('configured 2');
    expect(page).toContain('not in config.json: default 1');
    expect(page).toContain('held by:');
    expect(page).toContain('for 2 min');
    expect(page).toContain('waiting 14 min');
    expect(page).toContain('waiting 6 min');
    expect(page).toContain('J20261008-P4TX');
  });

  it('has a capacity field for each slot and a move up / down for each waiting job, the first not up and the last not down', () => {
    const page = html();
    expect(page.match(/type="number"/g)).toHaveLength(2);
    const disabled = (label: string) => page.split('<button').find(b => b.includes(`aria-label="${label}"`))!.split('>')[0].includes('disabled=""');
    expect(page).toContain('aria-label="Move J20261008-P4TX up"');
    expect(disabled('Move J20261008-P4TX up')).toBe(true);
    expect(disabled('Move J20261008-P4TX down')).toBe(false);
    expect(disabled('Move J20261008-R7HB up')).toBe(false);
    expect(disabled('Move J20261008-R7HB down')).toBe(true);
  });

  it('offers no move for a running job', () => {
    expect(html()).not.toContain('Move J20261008-K2QF');
  });
});
