import test from "node:test";
import assert from "node:assert/strict";

import { D1Store } from "../src/d1-store.js";
import { makeEnvironment, newCodes } from "./helpers.js";
import { createD1Like } from "./d1-sqlite.js";

async function d1Environment(options = {}) {
  const d1 = createD1Like();
  const store = new D1Store(d1);
  const env = await makeEnvironment({ ...options, store });
  return { d1, store, env };
}

test("esquema D1: importar, canjear y persistir la licencia", async () => {
  const { env, store } = await d1Environment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  const result = await client.activate(code);

  const row = await store.getByLicenseId(result.license.licenseId);
  assert.ok(row);
  assert.equal(row.active, true);
  assert.equal(row.appId, "DSHALEV");

  const codeRow = await store.getCode(row.codeHash);
  assert.equal(codeRow.state, "UNUSED");
  assert.ok(!JSON.stringify(codeRow).includes(code.replace(/-/g, "")));
});

test("esquema D1: request_id y code_hash activos son unicos", async () => {
  const { env, store, d1 } = await d1Environment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  const requestId = crypto.randomUUID();
  const results = await Promise.all(
    Array.from({ length: 5 }, () => client.activate(code, { requestId })),
  );
  assert.equal(new Set(results.map((r) => r.license.licenseId)).size, 1);

  const codeHash = d1.raw.prepare("SELECT code_hash FROM codes LIMIT 1").get().code_hash;
  const active = await store.getActiveByCode(codeHash);
  assert.ok(active);
  const second = await env.makeClient();
  await assert.rejects(
    () => second.client.activate(code),
    (error) => error.code === "CODE_ALREADY_REDEEMED",
  );
});

test("esquema D1: la transferencia libera el indice activo y conserva el vencimiento", async () => {
  const { env, store, d1 } = await d1Environment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const a = await env.makeClient();
  const b = await env.makeClient();
  await b.client.ready;
  const original = await a.client.activate(code);

  const codeHash = d1.raw.prepare("SELECT code_hash FROM codes LIMIT 1").get().code_hash;
  await store.deactivateForTransfer(original.license.licenseId, b.client.deviceThumbprint);
  const pending = await store.getPendingTransfer(codeHash);
  assert.equal(pending.toThumbprint, b.client.deviceThumbprint);

  const transferred = await b.client.activate(code);
  assert.equal(transferred.license.expiresAt, original.license.expiresAt);
  assert.equal(await store.getPendingTransfer(codeHash), null);

  const activeRows = d1.raw
    .prepare("SELECT COUNT(*) AS n FROM redemptions WHERE active = 1 AND code_hash = ?")
    .get(codeHash);
  assert.equal(activeRows.n, 1);
});

test("esquema D1: contadores por ventana", async () => {
  const { store } = await d1Environment();
  assert.equal(await store.incrementCounter("k", 1000), 1);
  assert.equal(await store.incrementCounter("k", 1000), 2);
  assert.equal(await store.incrementCounter("k", 2000), 1);
});

test("esquema D1: importar dos veces el mismo lote no duplica codigos", async () => {
  const { env, store, d1 } = await d1Environment();
  const [code] = newCodes(1);
  const batch = await env.makeBatch([{ code, type: "MENSUAL", secundarias: 0 }]);
  const first = await env.service.importBatch(batch);
  const second = await env.service.importBatch(batch);
  assert.equal(first.imported, 1);
  assert.equal(second.imported, 0);
  assert.equal(second.skipped, 1);
  const count = d1.raw.prepare("SELECT COUNT(*) AS n FROM codes").get().n;
  assert.equal(count, 1);
});
