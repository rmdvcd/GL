import test from 'node:test';
import assert from 'node:assert/strict';
import { makeEnvironment, newCodes } from './helpers.js';
import { D1Store } from '../src/d1-store.js';
import { createD1Like } from './d1-sqlite.js';

for (const d1 of [false, true]) test(`revocación por código llega al dispositivo (D1=${d1})`, async () => {
  const env = await makeEnvironment({store: d1 ? new D1Store(createD1Like()) : undefined});
  const [code] = newCodes();
  await env.importCodes([code]);
  const {client} = await env.makeClient();
  const {license} = await client.activate(code);
  await env.service.revokeCode(code);
  assert.equal((await env.store.getByLicenseId(license.licenseId)).active, false);
  assert.equal((await client.refreshStatus()).status, 'REVOCADA');
});
