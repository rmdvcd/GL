import test from "node:test";
import assert from "node:assert/strict";

import { makeEnvironment, newCodes } from "./helpers.js";
import { signDer } from "../src/keys.js";
import { toBase64 } from "../src/canonical.js";
import { redeemProofBytes } from "../src/protocol.js";

test("el mismo requestId devuelve siempre la misma licencia", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();

  const requestId = crypto.randomUUID();
  const first = await client.activate(code, { requestId });
  const second = await client.activate(code, { requestId });
  const third = await client.activate(code, { requestId });

  assert.equal(second.license.licenseId, first.license.licenseId);
  assert.equal(third.license.licenseId, first.license.licenseId);
  assert.equal(third.license.expiresAt, first.license.expiresAt);
  assert.equal(second.restored, true);
});

test("peticiones concurrentes con el mismo requestId producen una sola licencia", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();

  const requestId = crypto.randomUUID();
  const results = await Promise.all(
    Array.from({ length: 8 }, () => client.activate(code, { requestId })),
  );
  const ids = new Set(results.map((result) => result.license.licenseId));
  assert.equal(ids.size, 1);
});

test("peticiones concurrentes con requestId distinto y misma clave no duplican", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();

  const results = await Promise.all(
    Array.from({ length: 6 }, () => client.activate(code)),
  );
  const ids = new Set(results.map((result) => result.license.licenseId));
  assert.equal(ids.size, 1);
  assert.equal(env.store.redemptions.size, 1);
});

test("reutilizar un requestId con otro codigo o dispositivo es conflicto", async () => {
  const env = await makeEnvironment();
  const [codeA, codeB] = newCodes(2);
  await env.importCodes([codeA, codeB]);
  const { client } = await env.makeClient();
  const requestId = crypto.randomUUID();
  await client.activate(codeA, { requestId });

  const { client: other } = await env.makeClient();
  await other.ready;
  const request = {
    v: 1,
    appId: "DSHALEV",
    code: codeB.replace(/-/g, ""),
    deviceKey: other.deviceKeyB64,
    requestId,
    requestedAt: new Date(env.nowRef.value).toISOString(),
    nonce: toBase64(crypto.getRandomValues(new Uint8Array(16))),
  };
  request.proof = toBase64(
    await signDer(other.deviceKeys.privateKey, redeemProofBytes(request)),
  );
  const response = await other.fetchImpl("https://redeem.test/v1/redeem", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(request),
  });
  assert.equal(response.status, 409);
  assert.equal((await response.json()).error.code, "IDEMPOTENCY_CONFLICT");
});

test("un reintento no consume un segundo codigo aunque cambie el requestId", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  await client.activate(code);
  await client.activate(code);
  const codeRow = [...env.store.codes.values()][0];
  assert.equal(codeRow.state, "UNUSED");
  assert.equal(env.store.redemptions.size, 1);
});
