import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { verify } from "../directory.mjs";
const v = JSON.parse(
  readFileSync(
    new URL("../../../docs/vectores/directorio-v1.json", import.meta.url),
  ),
);
test("vector de prueba e idempotencia", async () =>
  assert.equal(
    (await verify(JSON.stringify(v.envelope), [v.root], v.envelope)).seq,
    7,
  ));
for (const c of v.cases)
  test(c.name, async () => {
    const check = () =>
      verify(JSON.stringify(c.envelope), [v.root], v.envelope);
    if (c.expected) await check();
    else await assert.rejects(check);
  });
test("límite de tamaño en bytes", async () =>
  await assert.rejects(verify(" ".repeat(65537), [v.root]), /tamaño/));
