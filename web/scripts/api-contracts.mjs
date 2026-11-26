import { spawnSync } from 'node:child_process';
import {
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [mode, directory] = process.argv.slice(2);

if (mode === 'mark') {
  for (const file of readdirSync(directory, { recursive: true })) {
    if (!file.endsWith('.ts')) continue;
    const path = join(directory, file);
    writeFileSync(
      path,
      `import 'server-only';\n\n${readFileSync(path, 'utf8')}`,
    );
  }
} else if (mode === 'check') {
  const output = mkdtempSync(join(tmpdir(), 'ticketing-api-'));
  try {
    const generated = spawnSync('openapi-ts', {
      env: { ...process.env, API_CONTRACT_OUTPUT: output },
      stdio: 'inherit',
    });
    if (generated.error) throw generated.error;
    if (generated.status !== 0) {
      process.exitCode = generated.status ?? 1;
    } else {
      const diff = spawnSync(
        'git',
        [
          'diff',
          '--no-index',
          '--exit-code',
          '--',
          'src/server/api/generated',
          output,
        ],
        { stdio: 'inherit' },
      );
      if (diff.error) throw diff.error;
      process.exitCode = diff.status ?? 1;
    }
  } finally {
    rmSync(output, { recursive: true, force: true });
  }
} else {
  throw new Error('Unknown API contract script mode');
}
