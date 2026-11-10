import test from "node:test";
import assert from "node:assert/strict";

import { makeEnvironment, newCodes } from "./helpers.js";
import { StoreRateLimiter } from "../src/rate-limit.js";
import { createHandler } from "../src/http.js";

async function post(handler, body, headers = {}) {
  return handler(
    new Request("https://redeem.test/v1/redeem", {
      method: "POST",
      headers: { "content-type": "application/json", ...headers },
      body: typeof body === "string" ? body : JSON.stringify(body),
    }),
  );
}

test("health responde sin exponer configuracion", async () => {
  const env = await makeEnvironment();
  const response = await env.handler(new Request("https://redeem.test/health"));
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.deepEqual(body, { ok: true, service: "gl-redeem", v: 1 });
  assert.equal(response.headers.get("cache-control"), "no-store");
});

test("rutas y metodos desconocidos devuelven errores uniformes", async () => {
  const env = await makeEnvironment();
  const notFound = await env.handler(new Request("https://redeem.test/v1/otra"));
  assert.equal(notFound.status, 404);
  assert.equal((await notFound.json()).error.code, "NOT_FOUND");

  const wrongMethod = await env.handler(
    new Request("https://redeem.test/v1/redeem", { method: "GET" }),
  );
  assert.equal(wrongMethod.status, 405);
  assert.equal((await wrongMethod.json()).error.code, "METHOD_NOT_ALLOWED");
  assert.equal(wrongMethod.headers.get("allow"), "POST, OPTIONS");
});

test("exige JSON, cuerpo no vacio y tamano limitado", async () => {
  const env = await makeEnvironment();
  const noType = await env.handler(
    new Request("https://redeem.test/v1/redeem", {
      method: "POST",
      headers: { "content-type": "text/plain" },
      body: "{}",
    }),
  );
  assert.equal(noType.status, 400);

  const malformed = await post(env.handler, "{");
  assert.equal(malformed.status, 400);

  const huge = await post(env.handler, { v: 1, padding: "x".repeat(20_000) });
  assert.equal(huge.status, 413);
  assert.equal((await huge.json()).error.code, "PAYLOAD_TOO_LARGE");

  const empty = await env.handler(
    new Request("https://redeem.test/v1/redeem", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "",
    }),
  );
  assert.equal(empty.status, 400);
});

test("los errores no incluyen stack ni detalles internos", async () => {
  const env = await makeEnvironment();
  const response = await post(env.handler, { v: 99 });
  assert.equal(response.status, 400);
  const text = await response.text();
  assert.ok(!text.includes("stack"));
  assert.ok(!text.includes("at "));
  const body = JSON.parse(text);
  assert.deepEqual(Object.keys(body.error).sort(), ["code", "message"]);
});

test("el limitador HTTP corta antes de procesar y no guarda la IP", async () => {
  const env = await makeEnvironment();
  const handler = createHandler({
    service: env.service,
    pepper: env.pepper,
    httpRateLimiter: new StoreRateLimiter({
      store: env.store,
      limit: 2,
      windowMs: 60_000,
    }),
  });
  const body = { v: 1 };
  const headers = { "cf-connecting-ip": "203.0.113.9" };
  assert.equal((await post(handler, body, headers)).status, 400);
  assert.equal((await post(handler, body, headers)).status, 400);
  const third = await post(handler, body, headers);
  assert.equal(third.status, 429);
  assert.equal((await third.json()).error.code, "RATE_LIMITED");

  const keys = [...env.store.counters.keys()].join(" ");
  assert.ok(!keys.includes("203.0.113.9"));
});

test("CORS solo se habilita si se configura", async () => {
  const env = await makeEnvironment();
  const without = await env.handler(new Request("https://redeem.test/health"));
  assert.equal(without.headers.get("access-control-allow-origin"), null);

  const withCors = createHandler({ service: env.service, allowOrigin: "https://app.test" });
  const response = await withCors(new Request("https://redeem.test/health"));
  assert.equal(response.headers.get("access-control-allow-origin"), "https://app.test");
});

test("un fallo inesperado del servicio no filtra el mensaje interno", async () => {
  const env = await makeEnvironment();
  const lines = [];
  const handler = createHandler({
    service: {
      ...env.service,
      redeem: async () => {
        throw new Error("secreto interno: 1234");
      },
      now: () => env.nowRef.value,
    },
    logger: (event, fields) => lines.push({ event, fields }),
  });
  const response = await post(handler, { v: 1, appId: "DSHALEV" });
  assert.equal(response.status, 500);
  const text = await response.text();
  assert.ok(!text.includes("1234"));
  assert.ok(!text.includes("secreto"));
});

test("canje HTTP real de extremo a extremo", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  const result = await client.activate(code);
  assert.equal(result.license.status, "ACTIVA");
});
