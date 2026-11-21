import test from "node:test";
import assert from "node:assert/strict";
import { makeEnvironment, newCodes } from "./helpers.js";
import { D1Store } from "../src/d1-store.js";
import { createD1Like } from "./d1-sqlite.js";

for (const d1 of [false, true])
  test(`revocación por código llega al dispositivo (D1=${d1})`, async () => {
    const env = await makeEnvironment({
      store: d1 ? new D1Store(createD1Like()) : undefined,
    });
    const [code] = newCodes();
    await env.importCodes([code]);
    const { client } = await env.makeClient();
    const { license } = await client.activate(code);
    await env.service.revokeCode(code);
    assert.equal(
      (await env.store.getByLicenseId(license.licenseId)).active,
      false,
    );
    assert.equal((await client.refreshStatus()).status, "REVOCADA");
  });

for (const d1 of [false, true])
  test(`revocar durante una reserva en curso bloquea la emisión (D1=${d1})`, async () => {
    const env = await makeEnvironment({
      store: d1 ? new D1Store(createD1Like()) : undefined,
    });
    const [code] = newCodes();
    await env.importCodes([code]);
    const claim = env.store.claim.bind(env.store);
    env.store.claim = async (...args) => {
      await env.service.revokeCode(code);
      return claim(...args);
    };
    const { client } = await env.makeClient();
    await assert.rejects(
      client.activate(code),
      (e) => e.code === "CODE_UNAVAILABLE",
    );
  });
