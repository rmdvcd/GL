import test from "node:test";
import assert from "node:assert/strict";

import { createNodeServer } from "../src/node-server.js";
import { makeEnvironment, newCodes } from "./helpers.js";
import { RedeemClient, MemoryLicenseStore } from "../src/client/redeem-client.js";
import { generateP256KeyPair } from "../src/keys.js";

async function listen(handler) {
  const server = createNodeServer(handler);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  return { server, baseUrl: `http://127.0.0.1:${port}` };
}

test("canje real por socket HTTP con el adaptador de Node", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { server, baseUrl } = await listen(env.handler);
  try {
    const deviceKeys = await generateP256KeyPair();
    const client = await RedeemClient.create({
      baseUrl,
      appId: "DSHALEV",
      pinnedKeys: { "gl-redeem-v1": env.servicePublic },
      deviceKeys,
      store: new MemoryLicenseStore(),
      fetchImpl: globalThis.fetch,
      now: () => env.nowRef.value,
    });

    const health = await fetch(`${baseUrl}/health`);
    assert.equal(health.status, 200);
    assert.equal((await health.json()).ok, true);

    const result = await client.activate(code);
    assert.equal(result.license.status, "ACTIVA");

    const stored = await client.loadLicense();
    assert.equal(stored.status, "ACTIVA");

    const doc = await client.refreshStatus();
    assert.equal(doc.status, "ACTIVA");
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("el adaptador de socket rechaza cuerpos mayores al limite", async () => {
  const env = await makeEnvironment();
  const { server, baseUrl } = await listen(env.handler);
  try {
    const response = await fetch(`${baseUrl}/v1/redeem`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ v: 1, padding: "x".repeat(40_000) }),
    });
    assert.equal(response.status, 413);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
