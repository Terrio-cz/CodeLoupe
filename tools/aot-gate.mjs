// The timing gate of the bundle smoke test (CL-249): the AOT cache against no archive and against the dynamic class-data archive.
// Medians of a dozen calls on a shared hosted runner differ by tens of milliseconds from run to run (macOS Intel measured AOT 510 ms
// against dynamic 456 ms), so the comparison with the dynamic archive has a margin. An unused or broken cache still fails: it runs
// about as slow as no archive, roughly twice the dynamic archive on every runner.

/** How much slower than the dynamic archive the AOT cache may measure before the gate fails. */
export const AOT_DYNAMIC_MARGIN = 0.15;

/** The reason the measured medians `{ none, dynamic, aot }` (ms) fail the gate, or null when they pass. */
export function aotGateFailure(cliMs) {
  const { none, dynamic, aot } = cliMs;
  if (![none, dynamic, aot].every(ms => Number.isFinite(ms) && ms > 0)) return `the CLI timings are incomplete: ${JSON.stringify(cliMs)}`;
  if (aot >= none) return `the AOT cache is not faster than no archive: ${JSON.stringify(cliMs)}`;
  if (aot > dynamic * (1 + AOT_DYNAMIC_MARGIN)) {
    return `the AOT cache is more than ${AOT_DYNAMIC_MARGIN * 100} % slower than the dynamic archive: ${JSON.stringify(cliMs)}`;
  }
  return null;
}
