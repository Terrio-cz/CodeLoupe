import assert from 'node:assert/strict';
import { test } from 'node:test';
import { render, skippedIn } from './skipped-tests.mjs';

const xml = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="codeloupe.platform.JobObjectsTest" tests="3" skipped="2" failures="0" errors="0">
  <testcase name="passes()" classname="codeloupe.platform.JobObjectsTest" time="0.002"></testcase>
  <testcase name="outside Windows &amp; there is no job()" classname="codeloupe.platform.JobObjectsTest" time="0.002">
    <skipped message="org.opentest4j.TestAbortedException: Assumption failed: Windows has job objects | really" type="org.opentest4j.TestAbortedException">trace
	at x</skipped>
  </testcase>
  <testcase name="bare()" classname="codeloupe.X" time="0"><skipped/></testcase>
  <testcase name="self closing()" classname="codeloupe.Y" time="0"/>
</testsuite>`;

test('finds the skipped cases with their reasons and leaves the others', () => {
  assert.deepEqual(skippedIn(xml), [
    { cls: 'JobObjectsTest', name: 'outside Windows & there is no job', reason: 'Windows has job objects | really' },
    { cls: 'X', name: 'bare', reason: 'no reason given' },
  ]);
});

test('renders a table, escaping the bar, or says nothing was skipped', () => {
  const text = render(skippedIn(xml), 'Skipped on x');
  assert.match(text, /^### Skipped on x\n\n2 skipped\./);
  assert.match(text, /\| JobObjectsTest \| outside Windows & there is no job \| Windows has job objects \\\| really \|/);
  assert.equal(render([], 'T'), '### T\n\nNothing was skipped.\n');
});
