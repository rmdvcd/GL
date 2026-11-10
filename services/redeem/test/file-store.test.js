import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { APP_ID, KID, makeEnvironment, newCodes } from "./helpers.js";
import { JsonFileStore } from "../src/file-store.js";
import { RedeemService } from "../src/service.js";
import { createHandler } from "../src/http.js";
import { generateP256KeyPair } from "../src/keys.js";
import { MemoryLicenseStore, RedeemClient } from "../src/client/redeem-client.js";

test("el almacen de fichero persiste la transferencia y sobrevive un reinicio", async () => {
  const dir = await mkdtemp(join(tmpdir(), "glreden-"));
  const path = join(dir, "store.json");
  try {
    const env = await makeEnvironment({ store: new JsonFileStore(path) });
    const [code] = newCodes(1);
    await env.importCodes([code]);

    const a = await env.makeClient();
    const original = await a.client.activate(code);

    const bKeys = await generateP256KeyPair();
    const b = await RedeemClient.create({
      baseUrl: "https://redeem.test",
      appId: APP_ID,
      pinnedKeys: { [KID]: env.servicePublic },
      deviceKeys: bKeys,
      store: new MemoryLicenseStore(),
      fetchImpl: env.serviceFetch,
      now: () => env.nowRef.value,
    });
    await b.ready;

    await env.service.authorizeTransfer(original.license.licenseId, b.deviceThumbprint);

    // Reinicio: almacen nuevo desde el mismo fichero y servicio nuevo con las
    // mismas claves; la transferencia debe seguir ahi.
    const restartedStore = new JsonFileStore(path);
    const restartedService = new RedeemService({
      store: restartedStore,
      pepper: env.pepper,
      glPublicKey: env.glKeys.publicKey,
      servicePrivateKey: env.serviceKeys.privateKey,
      servicePublicKey: env.serviceKeys.publicKey,
      serviceKid: KID,
      allowedApps: [APP_ID],
      now: () => env.nowRef.value,
      rateLimiter: null,
    });
    const handler = createHandler({ service: restartedService, pepper: env.pepper });
    const bClient = await RedeemClient.create({
      baseUrl: "https://redeem.test",
      appId: APP_ID,
      pinnedKeys: { [KID]: env.servicePublic },
      deviceKeys: bKeys,
      store: new MemoryLicenseStore(),
      fetchImpl: (url, init) => handler(new Request(url, init)),
      now: () => env.nowRef.value,
    });

    const transferred = await bClient.activate(code);
    assert.equal(transferred.license.transferredFrom, original.license.licenseId);
    assert.equal(transferred.license.expiresAt, original.license.expiresAt);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("un fichero corrupto falla en vez de fingir un almacen vacio", async () => {
  const dir = await mkdtemp(join(tmpdir(), "glreden-"));
  const path = join(dir, "store.json");
  try {
    await rm(path, { force: true });
    const { writeFile } = await import("node:fs/promises");
    await writeFile(path, "{no-json", "utf8");
    const store = new JsonFileStore(path);
    await assert.rejects(() => store.getCode("x"));
    await assert.rejects(() => store.getCode("x"));
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
