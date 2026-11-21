import test from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { join } from "node:path";

import { makeEnvironment, newCodes } from "./helpers.js";
import { RedeemClient, RedeemError } from "../src/client/redeem-client.js";
import { verifyLicenseEnvelope } from "../src/license.js";
import { importSpkiB64 } from "../src/keys.js";

const SPVI2 = "SPVI2:" + "A".repeat(210);

test("un codigo 4x4 valido por formato no activa nada", async () => {
  const env = await makeEnvironment();
  const { client } = await env.makeClient();
  await assert.rejects(
    () => client.activate("4K7N-9X2M-B8PQ-3T5V"),
    (error) => error instanceof RedeemError && error.code === "CODE_UNAVAILABLE",
  );
  assert.equal(await client.store.get(), null);
});

test("una cadena SPVI2 no se interpreta como licencia de canje", async () => {
  const env = await makeEnvironment();
  const { client } = await env.makeClient();
  await assert.rejects(
    () => client.activate(SPVI2),
    (error) => error.code === "INVALID_CODE",
  );
  const key = await importSpkiB64(env.servicePublic);
  await assert.rejects(
    () => verifyLicenseEnvelope(SPVI2, key),
    /invalid-license/,
  );
});

test("el verificador legacy es un punto de extension separado", async () => {
  const env = await makeEnvironment();
  const calls = [];
  const { client } = await env.makeClient({
    legacyVerifier: async (input) => {
      calls.push(input);
      return { valid: input === SPVI2 };
    },
  });

  const result = await client.verifyLegacy(SPVI2);
  assert.equal(result.valid, true);
  assert.deepEqual(calls, [SPVI2]);

  const { client: without } = await env.makeClient();
  await assert.rejects(
    () => without.verifyLegacy(SPVI2),
    (error) => error.code === "LEGACY_NOT_CONFIGURED",
  );
});

test("el cliente no contiene claves privadas de GL", async () => {
  const dir = join(import.meta.dirname, "../src/client/");
  const files = await readdir(dir);
  for (const file of files) {
    const content = await readFile(join(dir, file), "utf8");
    assert.ok(!content.includes("BEGIN PRIVATE KEY"), file);
    assert.ok(!content.includes("GL_ISSUER_PRIVATE"), file);
    assert.ok(!content.includes("gl.sign.ec"), file);
    assert.ok(!content.includes("gl.ecdh.p256"), file);
  }
});

test("un almacen manipulado no produce una activacion valida", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  await client.activate(code);
  const record = await client.store.get();
  record.license.license.status = "PERPETUA";
  record.license.license.expiresAt = null;
  await client.store.set(record);
  await assert.rejects(
    () => client.loadLicense(),
    (error) => error.code === "INVALID_LICENSE_SIGNATURE",
  );
});

test("el kid de firma de GL no es el kid de licencias del servicio", async () => {
  const env = await makeEnvironment();
  const { client } = await env.makeClient();
  await client.ready;
  assert.deepEqual(Object.keys(client.pinnedKeys), ["gl-redeem-v1"]);
  assert.ok(!("gl-sign-v1" in client.pinnedKeys));
});

test("el cliente de referencia puede generar codigos solo para utilidades de operador", async () => {
  assert.match(RedeemClient.generateCode(), /^[0-9A-HJ-NP-Z]{4}(-[0-9A-HJ-NP-Z]{4}){3}$/);
});
