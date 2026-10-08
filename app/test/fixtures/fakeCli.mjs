// Stands in for the CodeLoupe CLI in tests: records its arguments, environment home and stdin, answers like `env set`.
import fs from 'node:fs';

let stdin = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', d => { stdin += d; });
process.stdin.on('end', () => {
  const record = { argv: process.argv.slice(2), stdin, home: process.env.CODELOUPE_HOME };
  fs.writeFileSync(process.env.FAKE_CLI_OUT, JSON.stringify(record));
  if (process.env.FAKE_CLI_FAIL) {
    process.stderr.write(`failed with ${process.env.FAKE_CLI_FAIL}\n`);
    process.exit(2);
  }
  process.stdout.write('stored\n');
});
