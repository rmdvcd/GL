import test from "node:test";
import assert from "node:assert/strict";
import { makeEnvironment, newCodes } from "./helpers.js";
import { D1Store } from "../src/d1-store.js";
import { createD1Like } from "./d1-sqlite.js";

for (const d1 of [false, true])
  for (const transfer of [false, true])
    test(`cascada por instalación (D1=${d1}, transferencia=${transfer})`, async () => {
      const env = await makeEnvironment({
        store: d1 ? new D1Store(createD1Like()) : undefined,
      });
      const codes = newCodes(3);
      await env.importCodes(codes);
      const { client } = await env.makeClient();
      const other = await env.makeClient();
      const a = await client.activate(codes[0]);
      const b = await client.activate(codes[1]);
      await other.client.activate(codes[2]);
      if (transfer)
        await env.service.authorizeTransfer(
          b.license.licenseId,
          "A".repeat(43),
        );
      else await env.service.revokeLicense(b.license.licenseId);
      for (const { license } of [a, b]) {
        const row = await env.store.getByLicenseId(license.licenseId);
        assert.equal(row.active, false);
        assert.equal(row.status, "REVOCADA");
      }
      assert.equal((await other.client.refreshStatus()).status, "ACTIVA");
    });
