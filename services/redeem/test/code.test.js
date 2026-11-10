import test from "node:test";
import assert from "node:assert/strict";

import {
  CODE_ALPHABET,
  formatCode,
  generateNormalizedCode,
  hashCode,
  isValidCode,
  normalizeCode,
} from "../src/code.js";

test("normaliza guiones, espacios y minusculas", () => {
  assert.equal(normalizeCode(" 4k7n-9x2m b8pq-3t5v "), "4K7N9X2MB8PQ3T5V");
  assert.equal(formatCode("4K7N9X2MB8PQ3T5V"), "4K7N-9X2M-B8PQ-3T5V");
});

test("el alfabeto generado excluye I y O", () => {
  assert.equal(CODE_ALPHABET.length, 34);
  assert.ok(!CODE_ALPHABET.includes("I"));
  assert.ok(!CODE_ALPHABET.includes("O"));
  for (let i = 0; i < 50; i += 1) {
    const code = generateNormalizedCode();
    assert.equal(code.length, 16);
    assert.match(code, /^[0-9A-HJ-NP-Z]{16}$/);
  }
});

test("rechaza longitudes y simbolos fuera del alfabeto", () => {
  for (const bad of [
    "",
    "ABCD-EFGH-1234-567",
    "ABCD-EFGH-1234-56789",
    "ABCD-EFGH-1234-$$$$",
    "ABCD-EFGH-1234-IOIO",
    "A".repeat(65),
  ]) {
    assert.equal(isValidCode(bad), false, bad);
  }
});

test("la validez de formato no produce ningun identificador por si sola", async () => {
  const pepperA = new Uint8Array(32).fill(1);
  const pepperB = new Uint8Array(32).fill(2);
  const a = await hashCode(normalizeCode("4K7N-9X2M-B8PQ-3T5V"), pepperA);
  const b = await hashCode(normalizeCode("4K7N-9X2M-B8PQ-3T5V"), pepperB);
  assert.notEqual(a, b);
  assert.match(a, /^[A-Za-z0-9_-]{43}$/);
});

test("hashCode exige pepper de al menos 16 bytes", async () => {
  await assert.rejects(() => hashCode("4K7N9X2MB8PQ3T5V", new Uint8Array(8)));
});
