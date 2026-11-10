import test from "node:test";
import assert from "node:assert/strict";

import { APP_ID, DAY_MS, makeEnvironment, newCodes } from "./helpers.js";
import {
  MemoryLicenseStore,
  RedeemError,
  RedeemNetworkError,
} from "../src/client/redeem-client.js";

test("respuesta perdida tras el canje: no se guarda a medias y el reintento recupera", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client, licenseStore } = await env.makeClient();

  const realFetch = env.serviceFetch;
  let failNext = true;
  client.fetchImpl = async (url, init) => {
    const response = await realFetch(url, init);
    if (failNext) {
      failNext = false;
      throw new TypeError("socket-closed");
    }
    return response;
  };

  let lost;
  try {
    await client.activate(code);
    assert.fail("debio fallar");
  } catch (error) {
    lost = error;
  }
  assert.ok(lost instanceof RedeemNetworkError);
  assert.ok(lost.requestId);
  assert.equal(await licenseStore.get(), null);

  const retry = await client.activate(code, { requestId: lost.requestId });
  assert.equal(retry.restored, true);
  assert.ok(retry.license.licenseId);
  assert.ok(await licenseStore.get());
  assert.equal(env.store.redemptions.size, 1);
});

test("servidor inalcanzable: no persiste y un reintento nuevo funciona", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client, licenseStore } = await env.makeClient();
  client.fetchImpl = async () => {
    throw new TypeError("ECONNREFUSED");
  };
  await assert.rejects(
    () => client.activate(code),
    (error) => error.code === "NETWORK",
  );
  assert.equal(await licenseStore.get(), null);
  assert.equal(env.store.redemptions.size, 0);

  client.fetchImpl = env.serviceFetch;
  const result = await client.activate(code);
  assert.equal(result.license.status, "ACTIVA");
});

test("timeout antes de que el servidor procese no consume el codigo", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient({ timeoutMs: 20 });
  client.fetchImpl = (url, init) =>
    new Promise((resolve, reject) => {
      init.signal.addEventListener("abort", () => reject(new Error("aborted")));
    });
  await assert.rejects(
    () => client.activate(code),
    (error) => error.code === "NETWORK",
  );
  assert.equal(env.store.redemptions.size, 0);

  client.fetchImpl = env.serviceFetch;
  const result = await client.activate(code);
  assert.equal(result.license.status, "ACTIVA");
});

test("offline hasta el vencimiento; despues la licencia queda VENCIDA", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  const activated = await client.activate(code);

  env.nowRef.value = Date.parse(activated.license.expiresAt) - DAY_MS;
  let loaded = await client.loadLicense();
  assert.equal(loaded.status, "ACTIVA");

  env.nowRef.value = Date.parse(activated.license.expiresAt) + 1000;
  loaded = await client.loadLicense();
  assert.equal(loaded.status, "VENCIDA");
});

test("una licencia perpetua sigue valida sin conexion", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code], { type: "PERPETUA" });
  const { client } = await env.makeClient();
  await client.activate(code);
  env.nowRef.value += 10 * 365 * DAY_MS;
  const loaded = await client.loadLicense();
  assert.equal(loaded.status, "PERPETUA");
});

test("retroceder el reloj se detecta con la marca de agua", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  await client.activate(code);
  await client.recordSeen(env.nowRef.value);

  env.nowRef.value -= 30 * DAY_MS;
  await assert.rejects(
    () => client.loadLicense(),
    (error) => error.code === "CLOCK_ROLLBACK",
  );
  await assert.rejects(
    () => client.recordSeen(env.nowRef.value),
    (error) => error.code === "CLOCK_ROLLBACK",
  );
});

test("refreshStatus propaga la revocacion y loadLicense la conserva offline", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  const activated = await client.activate(code);

  await env.service.revokeLicense(activated.license.licenseId);
  const doc = await client.refreshStatus();
  assert.equal(doc.status, "REVOCADA");

  env.nowRef.value += 1000;
  const loaded = await client.loadLicense();
  assert.equal(loaded.status, "REVOCADA");
});

test("refreshStatus rechaza un estado manipulado", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  await client.activate(code);
  const originalFetch = client.fetchImpl;
  client.fetchImpl = async (url, init) => {
    const response = await originalFetch(url, init);
    const body = await response.json();
    body.status.status.status = "REVOCADA";
    return new Response(JSON.stringify(body), {
      status: response.status,
      headers: { "content-type": "application/json" },
    });
  };
  await assert.rejects(
    () => client.refreshStatus(),
    (error) => error.code === "INVALID_STATUS_SIGNATURE",
  );
});

test("una respuesta HTTP mal formada no se confunde con exito", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client, licenseStore } = await env.makeClient();
  client.fetchImpl = async () =>
    new Response("<html>gateway</html>", { status: 200 });
  await assert.rejects(
    () => client.activate(code),
    (error) => error instanceof RedeemError && error.code === "INVALID_RESPONSE",
  );
  assert.equal(await licenseStore.get(), null);
});

test("un reloj atrasado no activa si el almacen ya tiene una marca posterior", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const preloaded = new MemoryLicenseStore({
    v: 1,
    license: null,
    appId: APP_ID,
    deviceThumbprint: "x",
    lastSeenMs: env.nowRef.value + 10 * DAY_MS,
    status: "ACTIVA",
  });
  const { client } = await env.makeClient({ store: preloaded });
  await assert.rejects(
    () => client.activate(code),
    (error) => error.code === "CLOCK_ROLLBACK",
  );
});
