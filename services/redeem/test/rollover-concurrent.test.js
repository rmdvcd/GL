import test from "node:test";
import assert from "node:assert/strict";
import { makeEnvironment, newCodes, DAY_MS } from "./helpers.js";
import { D1Store } from "../src/d1-store.js";
import { createD1Like } from "./d1-sqlite.js";

test("dos canjes simultáneos conservan ambos períodos", async () => {
  const store = new D1Store(createD1Like());
  const env = await makeEnvironment({ store });
  // Sincronizar en la entrada a la transacción (después de validar las firmas).
  const claim = store.claim.bind(store);
  for (let i = 0; i < 20; i++) {
    const codes = newCodes(2);
    await env.importCodes(codes);
    const a = await env.makeClient();
    const b = await env.makeClient({ deviceKeys: a.deviceKeys });
    let count = 0,
      release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    store.claim = async (...args) => {
      if (++count === 2) release();
      await gate;
      return claim(...args);
    };
    const rows = await Promise.all([
      a.client.activate(codes[0]),
      b.client.activate(codes[1]),
    ]);
    assert.equal(
      Math.max(...rows.map((r) => Date.parse(r.license.expiresAt))),
      env.nowRef.value + 60 * DAY_MS,
    );
  }
});
