import test from "node:test";
import assert from "node:assert/strict";

import { DAY_MS, makeEnvironment, newCodes } from "./helpers.js";
import { RedeemError } from "../src/client/redeem-client.js";
import { generateP256KeyPair, signDer } from "../src/keys.js";
import { toBase64 } from "../src/canonical.js";
import { redeemProofBytes } from "../src/protocol.js";

test("canje feliz: licencia firmada, 30 dias, sin datos personales ni codigo", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();

  const result = await client.activate(code);
  assert.equal(result.restored, false);
  assert.equal(result.license.type, "MENSUAL");
  assert.equal(result.license.status, "ACTIVA");
  assert.equal(result.license.appId, "DSHALEV");
  assert.equal(
    Date.parse(result.license.expiresAt) - Date.parse(result.license.issuedAt),
    30 * DAY_MS,
  );

  const serialized = JSON.stringify(result.envelope);
  assert.ok(!serialized.includes(code.replace(/-/g, "")));
  assert.ok(!serialized.includes("nombre"));
  assert.ok(!serialized.includes("telefono"));
  assert.ok(!serialized.includes("deviceId"));
});

test("reintento con otro requestId en el mismo dispositivo restaura sin ampliar", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();

  const first = await client.activate(code);
  env.nowRef.value += 10 * DAY_MS;
  const second = await client.activate(code);

  assert.equal(second.restored, true);
  assert.equal(second.license.licenseId, first.license.licenseId);
  assert.equal(second.license.expiresAt, first.license.expiresAt);
  assert.equal(second.license.issuedAt, first.license.issuedAt);
});

test("otro dispositivo no puede canjear el mismo codigo", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const a = await env.makeClient();
  const b = await env.makeClient();

  await a.client.activate(code);
  await assert.rejects(
    () => b.client.activate(code),
    (error) => error instanceof RedeemError && error.code === "CODE_ALREADY_REDEEMED",
  );
});

test("codigo desconocido o mal formado responde igual y sin filtrar", async () => {
  const env = await makeEnvironment();
  const { client } = await env.makeClient();
  for (const code of [newCodes(1)[0], "ABCD-EFGH-1234-$$$$", "CORTO"]) {
    await assert.rejects(
      () => client.activate(code),
      (error) => error.code === "CODE_UNAVAILABLE" || error.code === "INVALID_CODE",
    );
  }
});

test("una prueba de posesion que firma otro codigo es rechazada", async () => {
  const env = await makeEnvironment();
  const [code, other] = newCodes(2);
  await env.importCodes([code]);
  const { client, deviceKeys } = await env.makeClient();
  await client.ready;

  const request = {
    v: 1,
    appId: "DSHALEV",
    code: other.replace(/-/g, ""),
    deviceKey: client.deviceKeyB64,
    requestId: crypto.randomUUID(),
    requestedAt: new Date(env.nowRef.value).toISOString(),
    nonce: toBase64(crypto.getRandomValues(new Uint8Array(16))),
  };
  request.proof = toBase64(
    await signDer(deviceKeys.privateKey, redeemProofBytes(request)),
  );

  const response = await client.fetchImpl("https://redeem.test/v1/redeem", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(request),
  });
  assert.equal(response.status, 404);
  assert.equal((await response.json()).error.code, "CODE_UNAVAILABLE");
});

test("un dispositivo sin prueba valida no obtiene licencia", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client, deviceKeys } = await env.makeClient();
  await client.ready;

  const request = {
    v: 1,
    appId: "DSHALEV",
    code: code.replace(/-/g, ""),
    deviceKey: client.deviceKeyB64,
    requestId: crypto.randomUUID(),
    requestedAt: new Date(env.nowRef.value).toISOString(),
    nonce: toBase64(crypto.getRandomValues(new Uint8Array(16))),
    proof: toBase64(await signDer(deviceKeys.privateKey, new Uint8Array([1, 2, 3]))),
  };
  const response = await client.fetchImpl("https://redeem.test/v1/redeem", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(request),
  });
  assert.equal(response.status, 401);
  const body = await response.json();
  assert.equal(body.error.code, "INVALID_PROOF");
});

test("una prueba con otra clave de dispositivo no sirve", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  const attacker = await generateP256KeyPair();

  const request = {
    v: 1,
    appId: "DSHALEV",
    code: code.replace(/-/g, ""),
    deviceKey: client.deviceKeyB64,
    requestId: crypto.randomUUID(),
    requestedAt: new Date(env.nowRef.value).toISOString(),
    nonce: toBase64(crypto.getRandomValues(new Uint8Array(16))),
  };
  request.proof = toBase64(await signDer(attacker.privateKey, redeemProofBytes(request)));
  const response = await client.fetchImpl("https://redeem.test/v1/redeem", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(request),
  });
  assert.equal(response.status, 401);
});

test("rechaza app distinta a la del lote", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client, deviceKeys } = await env.makeClient();
  await client.ready;
  const otherAppClient = await env.makeClient({ appId: "OTRA_APP" });

  const request = {
    v: 1,
    appId: "OTRA_APP",
    code: code.replace(/-/g, ""),
    deviceKey: client.deviceKeyB64,
    requestId: crypto.randomUUID(),
    requestedAt: new Date(env.nowRef.value).toISOString(),
    nonce: toBase64(crypto.getRandomValues(new Uint8Array(16))),
  };
  request.proof = toBase64(await signDer(deviceKeys.privateKey, redeemProofBytes(request)));
  const response = await client.fetchImpl("https://redeem.test/v1/redeem", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(request),
  });
  assert.equal(response.status, 404);
  assert.equal((await response.json()).error.code, "CODE_UNAVAILABLE");
  assert.ok(otherAppClient);
});

test("codigo con fecha limite pasada no se canjea", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code], { redeemBefore: new Date(env.nowRef.value - 1000).toISOString() });
  const { client } = await env.makeClient();
  await assert.rejects(
    () => client.activate(code),
    (error) => error.code === "CODE_EXPIRED",
  );
});

test("codigo revocado por el operador no se canjea", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  await env.service.revokeCode(code);
  const { client } = await env.makeClient();
  await assert.rejects(
    () => client.activate(code),
    (error) => error.code === "CODE_UNAVAILABLE",
  );
});

test("licencia perpetua no lleva vencimiento", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code], { type: "PERPETUA" });
  const { client } = await env.makeClient();
  const result = await client.activate(code);
  assert.equal(result.license.status, "PERPETUA");
  assert.equal(result.license.expiresAt, null);
});

test("el lote de otra app no se importa si la app no esta permitida", async () => {
  const env = await makeEnvironment({ allowedApps: ["DSHALEV"] });
  const [code] = newCodes(1);
  await assert.rejects(
    () => env.importCodes([code], { appId: "OTRA" }),
    (error) => error.code === "BATCH_INVALID",
  );
});

test("importar dos veces el mismo lote es idempotente; otro lote con el mismo codigo falla", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  const first = await env.makeBatch([{ code, type: "MENSUAL", secundarias: 0 }]);
  const a = await env.service.importBatch(first);
  const b = await env.service.importBatch(first);
  assert.equal(a.imported, 1);
  assert.equal(b.imported, 0);
  assert.equal(b.skipped, 1);

  const second = await env.makeBatch([{ code, type: "ANUAL", secundarias: 0 }]);
  await assert.rejects(
    () => env.service.importBatch(second),
    (error) => error.code === "BATCH_INVALID",
  );
});

test("un codigo nuevo conserva los dias restantes de la licencia activa", async () => {
  const env = await makeEnvironment();
  const [firstCode, secondCode] = newCodes(2);
  await env.importCodes([firstCode, secondCode]);
  const { client } = await env.makeClient();

  const first = await client.activate(firstCode);
  env.nowRef.value = Date.parse(first.license.expiresAt) - 20 * DAY_MS;
  const second = await client.activate(secondCode);

  assert.equal(
    second.license.expiresAt,
    new Date(Date.parse(first.license.expiresAt) + 30 * DAY_MS).toISOString(),
  );
  assert.equal(second.license.issuedAt, new Date(env.nowRef.value).toISOString());
});

test("si la licencia anterior ya vencio, el periodo nuevo empieza ahora", async () => {
  const env = await makeEnvironment();
  const [firstCode, secondCode] = newCodes(2);
  await env.importCodes([firstCode, secondCode]);
  const { client } = await env.makeClient();

  const first = await client.activate(firstCode);
  env.nowRef.value = Date.parse(first.license.expiresAt) + DAY_MS;
  const second = await client.activate(secondCode);

  assert.equal(
    second.license.expiresAt,
    new Date(env.nowRef.value + 30 * DAY_MS).toISOString(),
  );
});
