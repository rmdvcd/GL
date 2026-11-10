import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fromBase64, toBase64 } from "../src/canonical.js";
import { importSpkiB64, keyThumbprint, verifyDer } from "../src/keys.js";
import { redeemProofBytes, statusProofBytes } from "../src/protocol.js";
import { licenseSigningBytes, statusSigningBytes, verifyLicenseEnvelope, verifyStatusDoc } from "../src/license.js";
import { subTokenSigningBytes, verifySubToken } from "../src/subtoken.js";

test("vector publico de interoperabilidad DShalev: bytes y firmas", async () => {
  const v = JSON.parse(await readFile(new URL("./fixtures/dshalev-protocol-v1.json", import.meta.url)));
  const device = await importSpkiB64(v.request.deviceKey);
  const service = await importSpkiB64(v.servicePublicKey);
  for (const [name, bytes] of Object.entries({
    request: redeemProofBytes(v.request), statusRequest: statusProofBytes(v.statusRequest),
    license: licenseSigningBytes(v.license.license), status: statusSigningBytes(v.status.status),
    subToken: subTokenSigningBytes(v.subToken),
  })) assert.equal(toBase64(bytes), v.bytesBase64[name], name);
  assert.ok(await verifyDer(device, redeemProofBytes(v.request), fromBase64(v.request.proof)));
  assert.ok(await verifyDer(device, statusProofBytes(v.statusRequest), fromBase64(v.statusRequest.proof)));
  const payload = await verifyLicenseEnvelope(v.license, service);
  await verifyStatusDoc(v.status, service);
  const thumb = await keyThumbprint(fromBase64(v.secondaryPublicKey));
  await verifySubToken(v.subToken, payload, thumb, Date.parse("2026-11-12T00:00:00Z"));
  await assert.rejects(verifyLicenseEnvelope({ ...v.license, license: { ...payload, appId: "OTHER" } }, service));
  await assert.rejects(verifySubToken({ ...v.subToken, slot: 2 }, payload, thumb, Date.parse("2026-11-12T00:00:00Z")));
});
