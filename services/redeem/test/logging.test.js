import test from "node:test";
import assert from "node:assert/strict";

import { createLogger, LOG_ALLOWED_FIELDS } from "../src/log.js";
import { makeEnvironment, newCodes } from "./helpers.js";

function collector() {
  const lines = [];
  const log = createLogger({ sink: { log: (line) => lines.push(JSON.parse(line)) } });
  return { log, lines };
}

test("la lista blanca descarta codigos, pruebas, claves y datos personales", () => {
  const { log, lines } = collector();
  log("redeem_ok", {
    appId: "DSHALEV",
    type: "MENSUAL",
    code: "4K7N-9X2M-B8PQ-3T5V",
    deviceKey: "MFkw...",
    proof: "MEUCI...",
    nombre: "Carlos",
    telefono: "+5352345678",
    requestId: "abc",
    ip: "203.0.113.9",
  });
  assert.equal(lines.length, 1);
  const record = lines[0];
  assert.equal(record.event, "redeem_ok");
  assert.equal(record.appId, "DSHALEV");
  assert.equal(record.type, "MENSUAL");
  for (const forbidden of ["code", "deviceKey", "proof", "nombre", "telefono", "requestId", "ip"]) {
    assert.ok(!(forbidden in record), forbidden);
  }
  const serialized = JSON.stringify(record);
  assert.ok(!serialized.includes("4K7N"));
  assert.ok(!serialized.includes("203.0.113.9"));
  assert.ok(!LOG_ALLOWED_FIELDS.includes("code"));
});

test("un evento desconocido no se registra con su nombre arbitrario", () => {
  const { log, lines } = collector();
  log("intento_con_datos", { appId: "DSHALEV" });
  assert.equal(lines[0].event, "http_unexpected");
});

test("el servicio no registra codigos, claves ni identificadores de dispositivo", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  await env.importCodes([code]);
  const { client } = await env.makeClient();
  await client.activate(code);
  await client.activate(code);

  const serialized = JSON.stringify(env.logs);
  assert.ok(!serialized.includes(code.replace(/-/g, "")));
  assert.ok(!serialized.includes(client.deviceKeyB64));
  assert.ok(!serialized.includes(client.deviceThumbprint));
  assert.ok(!serialized.toLowerCase().includes("token"));
  assert.ok(!serialized.toLowerCase().includes("nonce"));
});

test("el error de un canje fallido tampoco filtra el codigo", async () => {
  const env = await makeEnvironment();
  const [code] = newCodes(1);
  const { client } = await env.makeClient();
  await client.activate(code).catch(() => {});
  const serialized = JSON.stringify(env.logs);
  assert.ok(!serialized.includes(code.replace(/-/g, "")));
});
