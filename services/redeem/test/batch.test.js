import test from "node:test";
import assert from "node:assert/strict";

import { createSignedBatch, verifyBatch } from "../src/batch.js";
import { generateP256KeyPair } from "../src/keys.js";
import { newCodes } from "./helpers.js";

const NOW = Date.parse("2026-11-10T12:00:00Z");

function verifySigned(batch, gl, extra = {}) {
  return verifyBatch(batch, { glPublicKey: gl.publicKey, maxSecundarias: 10, ...extra });
}

async function signedBatch(overrides = {}) {
  const gl = await generateP256KeyPair();
  const batch = await createSignedBatch({
    signingPrivateKey: gl.privateKey,
    appId: "DSHALEV",
    batchId: crypto.randomUUID(),
    createdAt: new Date(NOW).toISOString(),
    entries: newCodes(3).map((code) => ({ code, type: "MENSUAL", secundarias: 1 })),
    ...overrides,
  });
  return { gl, batch };
}

test("acepta un lote firmado y normaliza los codigos", async () => {
  const { gl, batch } = await signedBatch();
  const verified = await verifySigned(batch, gl, { nowMs: NOW });
  assert.equal(verified.entries.length, 3);
  for (const entry of verified.entries) {
    assert.match(entry.code, /^[0-9A-HJ-NP-Z]{16}$/);
    assert.equal(entry.secundarias, 1);
  }
});

test("rechaza si se altera un codigo del lote", async () => {
  const { gl, batch } = await signedBatch();
  batch.entries[0].code = newCodes(1)[0];
  await assert.rejects(
    () => verifySigned(batch, gl),
    /batch-error:signature/,
  );
});

test("rechaza si se altera el tipo o las secundarias", async () => {
  const { gl, batch } = await signedBatch();
  batch.entries[1].type = "PERPETUA";
  await assert.rejects(() => verifySigned(batch, gl), /signature/);

  const second = await signedBatch();
  second.batch.entries[1].secundarias = 10;
  await assert.rejects(
    () => verifySigned(second.batch, second.gl),
    /signature/,
  );
});

test("rechaza un lote firmado por otra clave", async () => {
  const { batch } = await signedBatch();
  const other = await generateP256KeyPair();
  await assert.rejects(
    () => verifySigned(batch, other),
    /batch-error:signature/,
  );
});

test("rechaza versiones, algoritmos o kid distintos", async () => {
  for (const mutate of [
    (b) => (b.v = 2),
    (b) => (b.alg = "ECIES-P256-AES256GCM-v1"),
    (b) => (b.issuerKid = "gl-redeem-v1"),
  ]) {
    const { gl, batch } = await signedBatch();
    mutate(batch);
    await assert.rejects(() => verifySigned(batch, gl), /batch-error/);
  }
});

test("rechaza codigos duplicados dentro del lote", async () => {
  const gl = await generateP256KeyPair();
  const code = newCodes(1)[0];
  const batch = await createSignedBatch({
    signingPrivateKey: gl.privateKey,
    appId: "DSHALEV",
    batchId: crypto.randomUUID(),
    createdAt: new Date(NOW).toISOString(),
    entries: [
      { code, type: "MENSUAL", secundarias: 0 },
      { code, type: "ANUAL", secundarias: 0 },
    ],
  });
  await assert.rejects(
    () => verifySigned(batch, gl),
    /duplicate-code/,
  );
});

test("rechaza app no permitida y lote vacio", async () => {
  const { gl, batch } = await signedBatch({ appId: "OTRA" });
  await assert.rejects(
    () => verifySigned(batch, gl, { allowedApps: ["DSHALEV"] }),
    /app-not-allowed/,
  );

  await assert.rejects(
    () =>
      createSignedBatch({
        signingPrivateKey: gl.privateKey,
        appId: "DSHALEV",
        batchId: crypto.randomUUID(),
        createdAt: new Date(NOW).toISOString(),
        entries: [],
      }).then((b) => verifySigned(b, gl)),
    /entries/,
  );
});

test("rechaza un deadline ya pasado solo si se exige futuro", async () => {
  const gl = await generateP256KeyPair();
  const batch = await createSignedBatch({
    signingPrivateKey: gl.privateKey,
    appId: "DSHALEV",
    batchId: crypto.randomUUID(),
    createdAt: new Date(NOW - 1000).toISOString(),
    entries: [
      {
        code: newCodes(1)[0],
        type: "MENSUAL",
        secundarias: 0,
        redeemBefore: new Date(NOW - 500).toISOString(),
      },
    ],
  });
  const ok = await verifySigned(batch, gl, { nowMs: NOW });
  assert.equal(ok.entries.length, 1);
  await assert.rejects(
    () =>
      verifySigned(batch, gl, {
        nowMs: NOW,
        requireFutureDeadline: true,
      }),
    /deadline-past/,
  );
});

test("limita el tamano del lote", async () => {
  const { gl, batch } = await signedBatch();
  await assert.rejects(
    () => verifySigned(batch, gl, { maxEntries: 2 }),
    /too-many-entries/,
  );
});

test("por defecto rechaza un lote con apps secundarias", async () => {
  const { gl, batch } = await signedBatch();
  await assert.rejects(
    () => verifyBatch(batch, { glPublicKey: gl.publicKey }),
    /secondary-not-allowed/,
  );
});
