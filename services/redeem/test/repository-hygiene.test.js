import { createHash } from 'node:crypto';
import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const cwd = fileURLToPath(new URL('../../../', import.meta.url));
test('el índice no contiene configuración local ni identificadores de Cloudflare', () => {
  const paths = execFileSync('git', ['ls-files', '-z'], { cwd, encoding: 'utf8' }).split('\0');
  assert.deepEqual(paths.filter(p => /(^|\/)(\.wrangler\/|wrangler\.production\.jsonc$)/i.test(p)), []);
  // Solo nombres de archivo en el diagnóstico, nunca valores. --cached no lee la copia local.
  const id = '([[:xdigit:]]{32}|[[:xdigit:]]{8}(-[[:xdigit:]]{4}){3}-[[:xdigit:]]{12})';
  const pattern = `(account_?id|database_?id|cloudflare_account_id)["'[:space:]]*[:=]["'[:space:]]*${id}|/accounts/${id}|/d1/database/${id}`;
  const result = spawnSync('git', ['grep', '--cached', '-IilE', pattern], { cwd, encoding: 'utf8' });
  assert.equal(result.status, 1, result.stdout || 'git grep no pudo revisar el índice');
});

test('no se reintroducen los identificadores retirados en otro archivo', () => {
  const fingerprints = new Set(["7633afcc061b51753373eb0d455dea2834c0263a6c4a9d50dd774bc5811134e4", "05ef363680c5d8d94315cd7577cb4e081572632071264ac0da2e17ded78188ca"]);
  const result = spawnSync('git', ['grep', '--cached', '-IhoiE', '[[:xdigit:]]{32}|[[:xdigit:]]{8}(-[[:xdigit:]]{4}){3}-[[:xdigit:]]{12}'], { cwd, encoding: 'utf8' });
  assert.ok([0, 1].includes(result.status));
  for (const token of result.stdout.trim().toLowerCase().split(/\s+/)) {
    assert.ok(!fingerprints.has(createHash('sha256').update(token).digest('hex')), 'ID de infraestructura en el índice');
  }
});
