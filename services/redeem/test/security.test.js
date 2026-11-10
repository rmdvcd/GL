import test from "node:test";
import assert from "node:assert/strict";

import { makeEnvironment, newCodes } from "./helpers.js";
import { RedeemClient, RedeemError } from "../src/client/redeem-client.js";
import { importSpkiB64, signDer, verifyDer } from "../src/keys.js";
import { fromBase64, toBase64 } from "../src/canonical.js";
import { licenseSigningBytes } from "../src/license.js";
import { batchSigningBytes } from "../src/batch.js";
import { generateP256KeyPair } from "../src/keys.js";

test("una licencia manipulada es rechazada aunque el formato sea correcto", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  const originalFetch = client.fetchImpl;
  client.fetchImpl = async (url, init) => {
    const response = await originalFetch(url, init);
    const body = await response.json();
    if (body.license) body.license.license.type = "PERPETUA";
    return new Response(JSON.stringify(body), {
      status: response.status,
      headers: { "content-type": "application/json" },
    });
  };

  await assert.rejects(
    () => client.activate(code),
    (error) => error instanceof RedeemError && error.code === "INVALID_LICENSE_SIGNATURE",
  );
  assert.equal(await client.store.get(), null);
});

test("una licencia firmada por otra clave es rechazada", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const attacker = await generateP256KeyPair();
  const { client } = await env.makeClient();
  const originalFetch = client.fetchImpl;
  client.fetchImpl = async (url, init) => {
    const response = await originalFetch(url, init);
    const body = await response.json();
    const forged = {
      ...body.license.license,
      expiresAt: new Date(Date.parse(body.license.license.expiresAt) + 365 * 86400000).toISOString(),
      status: "ACTIVA",
    };
    body.license.license = forged;
    body.license.sig = toBase64(await signDer(attacker.privateKey, licenseSigningBytes(forged)));
    return new Response(JSON.stringify(body), {
      status: response.status,
      headers: { "content-type": "application/json" },
    });
  };
  await assert.rejects(
    () => client.activate(code),
    (error) => error.code === "INVALID_LICENSE_SIGNATURE",
  );
});

test("una licencia de otro dispositivo es rechazada", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const a = await env.makeClient();
  const b = await env.makeClient();
  const originalFetch = b.client.fetchImpl;
  b.client.fetchImpl = async (url, init) => {
    const response = await originalFetch(url, init);
    const body = await response.json();
    // La licencia de A no verifica contra la clave de B ni su thumbprint.
    return new Response(JSON.stringify(body), {
      status: response.status,
      headers: { "content-type": "application/json" },
    });
  };
  await a.client.activate(code);
  // B no puede canjear; ademas, si le entregaran la licencia de A, el binding falla.
  const stolen = await a.client.store.get();
  await assert.rejects(
    async () => {
      await b.client.store.set(stolen);
      await b.client.loadLicense();
    },
    (error) => error.code === "INVALID_LICENSE_SIGNATURE" || error.code === "LICENSE_WRONG_DEVICE",
  );
});

test("un kid desconocido no se acepta", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  const originalFetch = client.fetchImpl;
  client.fetchImpl = async (url, init) => {
    const response = await originalFetch(url, init);
    const body = await response.json();
    body.license.kid = "otro-kid";
    return new Response(JSON.stringify(body), {
      status: response.status,
      headers: { "content-type": "application/json" },
    });
  };
  await assert.rejects(
    () => client.activate(code),
    (error) => error.code === "UNKNOWN_KID",
  );
});

test("las firmas de lote y de licencia no son intercambiables", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  const { batch } = await env.importCodes([code]);
  const { client } = await env.makeClient();
  const activated = await client.activate(code);

  const licenseSignature = fromBase64(activated.envelope.sig);
  const licensePayload = activated.envelope.license;
  assert.equal(
    await verifyDer(env.glKeys.publicKey, licenseSigningBytes(licensePayload), licenseSignature),
    false,
  );

  const batchSignature = fromBase64(batch.sig);
  const batchBytes = await batchSigningBytes(batch);
  assert.equal(await verifyDer(env.serviceKeys.publicKey, batchBytes, batchSignature), false);
});
test("el almacen no contiene el codigo en claro ni datos personales", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  await client.activate(code);

  const dump = JSON.stringify({
    codes: [...env.store.codes.entries()],
    redemptions: [...env.store.redemptions.values()],
  });
  assert.ok(!dump.includes(code.replace(/-/g, "")));
  assert.ok(!dump.includes("DSHALEV:"));
  assert.ok(!dump.toLowerCase().includes("nombre"));
});

test("un cuerpo con campos de mas o tipos raros se rechaza sin tocar el almacen", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  await client.ready;
  const base = {
    v: 1,
    appId: "DSHALEV",
    code: code.replace(/-/g, ""),
    deviceKey: client.deviceKeyB64,
    requestId: crypto.randomUUID(),
    requestedAt: new Date(env.nowRef.value).toISOString(),
    nonce: toBase64(crypto.getRandomValues(new Uint8Array(16))),
  };
  const oversized = { ...base, relleno: "x".repeat(1000) };
  const response = await client.fetchImpl("https://redeem.test/v1/redeem", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(oversized),
  });
  assert.equal(response.status, 400);
  assert.equal(env.store.redemptions.size, 0);
});

test("el parseo de claves publicas rechaza curvas y blobs invalidos", async () => {
  await assert.rejects(() => importSpkiB64("AAAA"));
});

test("el cliente exige una identidad de dispositivo persistida", async () => {
  await assert.rejects(
    () =>
      RedeemClient.create({
        baseUrl: "https://redeem.test",
        appId: "DSHALEV",
        pinnedKeys: { "gl-redeem-v1": "x" },
      }),
    /device-keys-required/,
  );
});
