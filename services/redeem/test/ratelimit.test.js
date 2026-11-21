import test from "node:test";
import assert from "node:assert/strict";

import { makeEnvironment, newCodes } from "./helpers.js";
import { RedeemError } from "../src/client/redeem-client.js";

test("el limitador por dispositivo corta los intentos repetidos", async () => {
  const env = await makeEnvironment({ rateLimit: 2 });
  const codes = newCodes(3);
  await env.importCodes(codes);
  const { client } = await env.makeClient();

  await client.activate(codes[0]);
  await client.activate(codes[1]);
  await assert.rejects(
    () => client.activate(codes[2]),
    (error) => error instanceof RedeemError && error.code === "RATE_LIMITED",
  );
});

test("un reintento idempotente cuenta como intento y puede limitarse", async () => {
  const env = await makeEnvironment({ rateLimit: 1 });
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  const requestId = crypto.randomUUID();
  await client.activate(code, { requestId });
  await assert.rejects(
    () => client.activate(code, { requestId }),
    (error) => error.code === "RATE_LIMITED",
  );
});

test("el limitador no guarda la IP ni el thumbprint en claro", async () => {
  const env = await makeEnvironment({ rateLimit: 2 });
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  await client.activate(code);
  const keys = [...env.store.counters.keys()].join(" ");
  assert.ok(!keys.includes(client.deviceThumbprint));
});

import { BindingRateLimiter } from '../src/rate-limit.js';
test('binding nativo: éxito, rechazo y fallo cerrado', async () => {
  assert.throws(()=>new BindingRateLimiter({},'RL_IP'), /missing-binding:RL_IP/);
  for (const success of [true,false]) {
    const limiter = new BindingRateLimiter({RL_IP:{limit:async ({key})=>({success:key==='hash' && success})}},'RL_IP');
    assert.equal(await limiter.check('hash'),success);
  }
  assert.equal(await new BindingRateLimiter({RL_IP:{limit(){throw Error()}}},'RL_IP').check('hash'),false);
});
