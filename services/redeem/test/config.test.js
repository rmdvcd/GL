import test from "node:test";
import assert from "node:assert/strict";

import { buildServiceFromEnv } from "../src/config.js";
import { MemoryStore } from "../src/store.js";
import { toBase64 } from "../src/canonical.js";
import { exportPkcs8B64, exportSpkiB64, generateP256KeyPair } from "../src/keys.js";

async function envFor() {
  const gl = await generateP256KeyPair();
  const service = await generateP256KeyPair();
  return {
    GL_ISSUER_PUBLIC_KEY: await exportSpkiB64(gl.publicKey),
    SERVICE_PUBLIC_KEY: await exportSpkiB64(service.publicKey),
    SERVICE_PRIVATE_KEY: await exportPkcs8B64(service.privateKey),
    CODE_PEPPER: toBase64(new Uint8Array(32).fill(9)),
    ALLOWED_APPS: "DSHALEV",
  };
}

test("construye el servicio con secretos completos", async () => {
  const env = await envFor();
  const built = await buildServiceFromEnv(env, {
    store: new MemoryStore(),
    logger: () => {},
    rateLimiter: null,
  });
  assert.ok(built.service);
  assert.deepEqual(built.service.allowedApps, ["DSHALEV"]);
  assert.equal(built.service.maxSecundarias, 0);
});

test("falla si falta un secreto obligatorio", async () => {
  const env = await envFor();
  delete env.CODE_PEPPER;
  await assert.rejects(
    () => buildServiceFromEnv(env, { store: new MemoryStore() }),
    /missing-env:CODE_PEPPER/,
  );
});

test("falla si la publica y la privada del servicio no son pareja", async () => {
  const env = await envFor();
  const other = await generateP256KeyPair();
  env.SERVICE_PUBLIC_KEY = await exportSpkiB64(other.publicKey);
  await assert.rejects(
    () => buildServiceFromEnv(env, { store: new MemoryStore() }),
    /SERVICE_KEYS_MISMATCH/,
  );
});

test("falla con pepper corto", async () => {
  const env = await envFor();
  env.CODE_PEPPER = toBase64(new Uint8Array(8).fill(1));
  await assert.rejects(
    () => buildServiceFromEnv(env, { store: new MemoryStore() }),
    /CODE_PEPPER/,
  );
});

test("falla con pepper de 16 bytes: el minimo es 32", async () => {
  const env = await envFor();
  env.CODE_PEPPER = toBase64(new Uint8Array(16).fill(1));
  await assert.rejects(
    () => buildServiceFromEnv(env, { store: new MemoryStore() }),
    /CODE_PEPPER/,
  );
});

test("rechaza MAX_SECUNDARIAS fuera de rango", async () => {
  const env = await envFor();
  env.MAX_SECUNDARIAS = "11";
  await assert.rejects(
    () => buildServiceFromEnv(env, { store: new MemoryStore() }),
    /MAX_SECUNDARIAS/,
  );
});

test("permite MAX_SECUNDARIAS explicito dentro de rango", async () => {
  const env = await envFor();
  env.MAX_SECUNDARIAS = "10";
  const built = await buildServiceFromEnv(env, {
    store: new MemoryStore(),
    logger: () => {},
    rateLimiter: null,
  });
  assert.equal(built.service.maxSecundarias, 10);
});
