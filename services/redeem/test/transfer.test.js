import test from "node:test";
import assert from "node:assert/strict";

import { DAY_MS, makeEnvironment, newCodes } from "./helpers.js";
import { RedeemError } from "../src/client/redeem-client.js";

test("transferencia autorizada conserva el vencimiento y revoca la licencia anterior", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const a = await env.makeClient();
  const b = await env.makeClient();

  const original = await a.client.activate(code);
  await b.client.ready;
  env.nowRef.value += 10 * DAY_MS;

  await env.service.authorizeTransfer(original.license.licenseId, b.client.deviceThumbprint);
  const status = await a.client.refreshStatus();
  assert.equal(status.status, "REVOCADA");

  const transferred = await b.client.activate(code);
  assert.equal(transferred.license.expiresAt, original.license.expiresAt);
  assert.equal(transferred.license.transferredFrom, original.license.licenseId);
  assert.notEqual(transferred.license.licenseId, original.license.licenseId);
  assert.equal(transferred.license.status, "ACTIVA");
});

test("tras la transferencia, el dispositivo anterior ya no recibe licencia nueva", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const a = await env.makeClient();
  const b = await env.makeClient();
  const original = await a.client.activate(code);
  await b.client.ready;
  await env.service.authorizeTransfer(original.license.licenseId, b.client.deviceThumbprint);

  // Antes de que B canjee, A no puede recuperar el codigo.
  await assert.rejects(
    () => a.client.activate(code),
    (error) => error.code === "CODE_ALREADY_REDEEMED",
  );

  const transferred = await b.client.activate(code);
  assert.equal(transferred.license.transferredFrom, original.license.licenseId);

  // El equipo A conserva su licencia firmada, pero el estado online la marca revocada.
  const status = await a.client.refreshStatus();
  assert.equal(status.status, "REVOCADA");
});

test("no se transfiere una licencia ya vencida para regalar un periodo nuevo", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const a = await env.makeClient();
  const b = await env.makeClient();
  const original = await a.client.activate(code);
  await b.client.ready;
  env.nowRef.value = Date.parse(original.license.expiresAt) + 1000;
  await env.service.authorizeTransfer(original.license.licenseId, b.client.deviceThumbprint);

  await assert.rejects(
    () => b.client.activate(code),
    (error) => error instanceof RedeemError && error.code === "CODE_EXPIRED",
  );
});

test("restaurar en el mismo dispositivo devuelve la misma licencia sin consumir el codigo", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client, deviceKeys } = await env.makeClient();
  const original = await client.activate(code);

  // Simula reinstalacion: se pierde el almacen local pero la clave del
  // dispositivo sigue en el Keystore.
  const { RedeemClient, MemoryLicenseStore } = await import("../src/client/redeem-client.js");
  const restoredClient = await RedeemClient.create({
    baseUrl: "https://redeem.test",
    appId: "DSHALEV",
    pinnedKeys: { "gl-redeem-v1": env.servicePublic },
    deviceKeys,
    store: new MemoryLicenseStore(),
    fetchImpl: env.serviceFetch,
    now: () => env.nowRef.value,
  });
  const restored = await restoredClient.activate(code);
  assert.equal(restored.license.licenseId, original.license.licenseId);
  assert.equal(restored.license.expiresAt, original.license.expiresAt);
  assert.equal(restored.restored, true);
});

test("revocar una licencia impide transferirla despues", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const a = await env.makeClient();
  const original = await a.client.activate(code);
  await env.service.revokeLicense(original.license.licenseId);
  const b = await env.makeClient();
  await b.client.ready;
  await assert.rejects(
    () => env.service.authorizeTransfer(original.license.licenseId, b.client.deviceThumbprint),
    (error) => error.code === "LICENSE_NOT_FOUND",
  );
  await assert.rejects(
    () => b.client.activate(code),
    (error) => error.code === "CODE_UNAVAILABLE",
  );
});

test("una licencia perpetua tambien exige autorizacion y destino concreto", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code], { type: "PERPETUA" });
  const a = await env.makeClient();
  const b = await env.makeClient();
  await b.client.ready;
  const original = await a.client.activate(code);
  assert.equal(original.license.expiresAt, null);

  await env.service.authorizeTransfer(original.license.licenseId, b.client.deviceThumbprint);
  await assert.rejects(
    () => a.client.activate(code),
    (error) => error.code === "CODE_ALREADY_REDEEMED",
  );
  const transferred = await b.client.activate(code);
  assert.equal(transferred.license.status, "PERPETUA");
  assert.equal(transferred.license.expiresAt, null);
  assert.equal(transferred.license.transferredFrom, original.license.licenseId);
});

test("sin autorizacion previa, un canje inactivo no se puede reclamar", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const a = await env.makeClient();
  const original = await a.client.activate(code);
  await env.store.setStatus(original.license.licenseId, "REVOCADA");

  const b = await env.makeClient();
  await assert.rejects(
    () => b.client.activate(code),
    (error) => error.code === "CODE_UNAVAILABLE",
  );
});

test("se puede corregir el destino de una transferencia autorizada", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const a = await env.makeClient();
  const b = await env.makeClient();
  await b.client.ready;
  const original = await a.client.activate(code);

  const wrong = "A".repeat(43);
  await env.service.authorizeTransfer(original.license.licenseId, wrong);
  await assert.rejects(
    () => b.client.activate(code),
    (error) => error.code === "CODE_ALREADY_REDEEMED",
  );

  await env.service.authorizeTransfer(original.license.licenseId, b.client.deviceThumbprint);
  const transferred = await b.client.activate(code);
  assert.equal(transferred.license.transferredFrom, original.license.licenseId);
});
