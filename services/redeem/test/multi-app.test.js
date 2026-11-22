import test from "node:test";
import assert from "node:assert/strict";

import { DAY_MS, makeEnvironment, newCodes } from "./helpers.js";
import { RedeemError } from "../src/client/redeem-client.js";
import { buildServiceFromEnv } from "../src/config.js";
import { toBase64 } from "../src/canonical.js";
import { exportPkcs8B64, exportSpkiB64, generateP256KeyPair } from "../src/keys.js";

const APPS = ["DSHALEV", "SHALEV-ANDROID", "SHALEV-IOS"];

test("ALLOWED_APPS acepta los tres appId de Shalev y el validador los admite", async () => {
  const env = await makeEnvironment({ allowedApps: APPS, maxSecundarias: 0 });
  assert.deepEqual(env.service.allowedApps, APPS);

  for (const appId of APPS) {
    const [code] = newCodes(1);
    const { result } = await env.importCodes([code], { appId });
    assert.equal(result.imported, 1, `lote de ${appId}`);
  }
});

test("un lote de SHALEV-ANDROID se canjea con appId SHALEV-ANDROID", async () => {
  const env = await makeEnvironment({ allowedApps: APPS });
  const [code] = newCodes(1);
  await env.importCodes([code], { appId: "SHALEV-ANDROID" });
  const { client } = await env.makeClient({ appId: "SHALEV-ANDROID" });

  const result = await client.activate(code);

  assert.equal(result.license.appId, "SHALEV-ANDROID");
  assert.equal(
    Date.parse(result.license.expiresAt) - Date.parse(result.license.issuedAt),
    30 * DAY_MS,
  );
});

test("el mismo codigo con otro appId devuelve CODE_UNAVAILABLE", async () => {
  const env = await makeEnvironment({ allowedApps: APPS });
  const [code] = newCodes(1);
  await env.importCodes([code], { appId: "SHALEV-ANDROID" });

  for (const intruso of ["DSHALEV", "SHALEV-IOS"]) {
    const { client } = await env.makeClient({ appId: intruso });
    await assert.rejects(
      () => client.activate(code),
      (error) => error instanceof RedeemError && error.code === "CODE_UNAVAILABLE",
      `${intruso} no debe canjear un codigo de SHALEV-ANDROID`,
    );
  }

  const propio = await env.makeClient({ appId: "SHALEV-ANDROID" });
  const ok = await propio.client.activate(code);
  assert.equal(ok.license.appId, "SHALEV-ANDROID");
});

test("una misma instalacion acumula licencias en apps distintas sin mezclar rollover ni cascada", async () => {
  const env = await makeEnvironment({ allowedApps: APPS });
  const [codeD, codeA, codeI] = newCodes(3);
  await env.importCodes([codeD], { appId: "DSHALEV" });
  await env.importCodes([codeA], { appId: "SHALEV-ANDROID" });
  await env.importCodes([codeI], { appId: "SHALEV-IOS" });

  const device = await env.makeClient({ appId: "DSHALEV" });
  const licenciaD = await device.client.activate(codeD);
  env.nowRef.value += 5 * DAY_MS;

  const android = await env.makeClient({ appId: "SHALEV-ANDROID", deviceKeys: device.deviceKeys });
  const licenciaA = await android.client.activate(codeA);

  // Cada app conserva su propio vencimiento: el canje de Android no hereda los dias de DSHALEV.
  assert.equal(licenciaD.license.appId, "DSHALEV");
  assert.equal(licenciaA.license.appId, "SHALEV-ANDROID");
  assert.equal(
    Date.parse(licenciaA.license.expiresAt) - Date.parse(licenciaA.license.issuedAt),
    30 * DAY_MS,
  );

  // La cascada es por code_hash: revocar DSHALEV no toca la licencia Android de la misma clave.
  await env.service.revokeLicense(licenciaD.license.licenseId);
  const estadoAndroid = await env.service.store.getByLicenseId(licenciaA.license.licenseId);
  assert.equal(estadoAndroid.active, true);
  assert.equal(estadoAndroid.status, "ACTIVA");

  const estadoD = await env.service.store.getByLicenseId(licenciaD.license.licenseId);
  assert.equal(estadoD.status, "REVOCADA");
});

test("MAX_SECUNDARIAS=0 se aplica a los lotes de todas las apps", async () => {
  const env = await makeEnvironment({ allowedApps: APPS, maxSecundarias: 0 });

  for (const appId of APPS) {
    const [code] = newCodes(1);
    await assert.rejects(
      () => env.importCodes([code], { appId, secundarias: 1 }),
      (error) => error.code === "BATCH_INVALID" && /secondary-not-allowed/.test(error.detail ?? ""),
      `${appId} no debe aceptar secundarias con MAX_SECUNDARIAS=0`,
    );
  }
});

test("el lote se valida antes de tocar el almacen: otra app se rechaza sin importar nada", async () => {
  const env = await makeEnvironment({ allowedApps: APPS });

  // Cada lote de las tres apps se importa con su propio appId.
  for (const appId of APPS) {
    const [code] = newCodes(1);
    const { result } = await env.importCodes([code], { appId });
    assert.equal(result.imported, 1, appId);
  }

  const [otra] = newCodes(1);
  let llamadasAlAlmacen = 0;
  const original = env.store.importCodes.bind(env.store);
  env.store.importCodes = async (...args) => {
    llamadasAlAlmacen += 1;
    return original(...args);
  };

  await assert.rejects(
    () => env.importCodes([otra], { appId: "OTRA" }),
    (error) => error.code === "BATCH_INVALID" && error.detail === "batch-error:app-not-allowed",
  );
  assert.equal(llamadasAlAlmacen, 0);
});

test("ALLOWED_APPS se lee de la variable de entorno con los tres appId de Shalev", async () => {
  const base = await makeEnvironment({ allowedApps: APPS });
  const env = {
    ALLOWED_APPS: "DSHALEV, SHALEV-ANDROID ,SHALEV-IOS",
    MAX_SECUNDARIAS: "0",
    CODE_PEPPER: toBase64(crypto.getRandomValues(new Uint8Array(32))),
    GL_ISSUER_PUBLIC_KEY: await exportSpkiB64(await generateP256KeyPair().then((k) => k.publicKey)),
    SERVICE_PUBLIC_KEY: await exportSpkiB64(base.serviceKeys.publicKey),
    SERVICE_PRIVATE_KEY: await exportPkcs8B64(base.serviceKeys.privateKey),
  };
  const built = await buildServiceFromEnv(env, {
    store: base.store,
    logger: { log: () => {} },
    rateLimiter: null,
  });
  assert.deepEqual(built.service.allowedApps, APPS);
  assert.equal(built.service.maxSecundarias, 0);
});
