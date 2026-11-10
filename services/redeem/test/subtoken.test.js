import test from "node:test";
import assert from "node:assert/strict";

import { APP_ID, KID, makeEnvironment, newCodes } from "./helpers.js";
import { generateP256KeyPair } from "../src/keys.js";
import { MemoryLicenseStore, RedeemClient } from "../src/client/redeem-client.js";

async function makeSecondary(env) {
  const keys = await generateP256KeyPair();
  const client = await RedeemClient.create({
    baseUrl: "https://redeem.test",
    appId: APP_ID,
    pinnedKeys: { [KID]: env.servicePublic },
    deviceKeys: keys,
    store: new MemoryLicenseStore(),
    fetchImpl: env.serviceFetch,
    now: () => env.nowRef.value,
  });
  await client.ready;
  return { client, keys };
}

test("licencia primaria con secundarias y activacion offline de una secundaria", async () => {
  const env = await makeEnvironment({ maxSecundarias: 2 });
  const [code] = newCodes(1);
  await env.importCodes([code], { secundarias: 2 });
  const primary = await env.makeClient();
  const issued = await primary.client.activate(code);
  assert.equal(issued.license.secundarias, 2);
  assert.ok(issued.license.devicePublicKey);

  const secondary = await makeSecondary(env);
  const bundle = await primary.client.exportSubLicense(secondary.client.deviceThumbprint, 1);
  assert.equal(bundle.v, 1);
  assert.equal(bundle.subToken.allowed, 2);
  assert.equal(bundle.subToken.slot, 1);

  const activated = await secondary.client.activateSubLicense(bundle);
  assert.equal(activated.kind, "SUB");
  assert.equal(activated.license.licenseId, issued.license.licenseId);
  assert.equal(activated.status, "ACTIVA");

  const offline = await secondary.client.loadLicense();
  assert.equal(offline.kind, "SUB");
  assert.equal(offline.status, "ACTIVA");
  assert.equal(offline.subToken.slot, 1);
});

test("un permiso manipulado o para otro dispositivo se rechaza", async () => {
  const env = await makeEnvironment({ maxSecundarias: 2 });
  const [code] = newCodes(1);
  await env.importCodes([code], { secundarias: 2 });
  const primary = await env.makeClient();
  await primary.client.activate(code);

  const secondary = await makeSecondary(env);
  const bundle = await primary.client.exportSubLicense(secondary.client.deviceThumbprint, 1);

  const tampered = structuredClone(bundle);
  tampered.subToken.slot = 2;
  await assert.rejects(
    () => secondary.client.activateSubLicense(tampered, { replace: true }),
    (error) => error.code === "INVALID_SUBTOKEN",
  );

  const other = await makeSecondary(env);
  await assert.rejects(
    () => other.client.activateSubLicense(bundle),
    (error) => error.code === "INVALID_SUBTOKEN",
  );

  const extended = structuredClone(bundle);
  extended.subToken.expiresAt = "2099-01-01T00:00:00.000Z";
  await assert.rejects(
    () => secondary.client.activateSubLicense(extended, { replace: true }),
    (error) => error.code === "INVALID_SUBTOKEN",
  );

  const allowedTampered = structuredClone(bundle);
  allowedTampered.subToken.allowed = 1;
  await assert.rejects(
    () => secondary.client.activateSubLicense(allowedTampered, { replace: true }),
    (error) => error.code === "INVALID_SUBTOKEN",
  );

  const slotOutOfRange = await makeSecondary(env);
  await assert.rejects(
    () => primary.client.exportSubLicense(slotOutOfRange.client.deviceThumbprint, 3),
    (error) => error.code === "INVALID_SUBTOKEN_REQUEST",
  );
});

test("una licencia primaria con firma invalida no activa una secundaria", async () => {
  const env = await makeEnvironment({ maxSecundarias: 1 });
  const [code] = newCodes(1);
  await env.importCodes([code], { secundarias: 1 });
  const primary = await env.makeClient();
  await primary.client.activate(code);
  const secondary = await makeSecondary(env);
  const bundle = await primary.client.exportSubLicense(secondary.client.deviceThumbprint, 1);

  const tampered = structuredClone(bundle);
  tampered.primaryLicense.sig = "AAAA";
  await assert.rejects(
    () => secondary.client.activateSubLicense(tampered),
    (error) => error.code === "INVALID_LICENSE_SIGNATURE",
  );
});

test("una primaria sin secundarias no puede emitir permisos", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code], { secundarias: 0 });
  const primary = await env.makeClient();
  await primary.client.activate(code);

  const secondary = await makeSecondary(env);
  await assert.rejects(
    () => primary.client.exportSubLicense(secondary.client.deviceThumbprint, 1),
    (error) => error.code === "NO_SECONDARIES",
  );
});

test("una secundaria no consulta /v1/status como si fuera la primaria", async () => {
  const env = await makeEnvironment({ maxSecundarias: 1 });
  const [code] = newCodes(1);
  await env.importCodes([code], { secundarias: 1 });
  const primary = await env.makeClient();
  await primary.client.activate(code);
  const secondary = await makeSecondary(env);
  const bundle = await primary.client.exportSubLicense(secondary.client.deviceThumbprint, 1);
  await secondary.client.activateSubLicense(bundle);

  await assert.rejects(
    () => secondary.client.refreshStatus(),
    (error) => error.code === "SUBTOKEN_STATUS_UNSUPPORTED",
  );
});

test("al vencer la primaria, la licencia secundaria queda VENCIDA", async () => {
  const env = await makeEnvironment({ maxSecundarias: 1 });
  const [code] = newCodes(1);
  await env.importCodes([code], { secundarias: 1 });
  const primary = await env.makeClient();
  const issued = await primary.client.activate(code);
  const secondary = await makeSecondary(env);
  const bundle = await primary.client.exportSubLicense(secondary.client.deviceThumbprint, 1);
  await secondary.client.activateSubLicense(bundle);

  env.nowRef.value = Date.parse(issued.license.expiresAt) + 1000;
  const offline = await secondary.client.loadLicense();
  assert.equal(offline.status, "VENCIDA");
  await assert.rejects(
    () => secondary.client.activateSubLicense(bundle, { replace: true }),
    (error) => error.code === "LICENSE_EXPIRED",
  );
});
