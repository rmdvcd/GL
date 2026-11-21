import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { makeEnvironment, newCodes } from "./helpers.js";
import { createD1Like } from "./d1-sqlite.js";
test("SQL de operador ejecutable en D1; import reintentable", async (t) => {
  const env = await makeEnvironment(),
    db = createD1Like().raw;
  const dir = mkdtempSync(join(tmpdir(), "operator-"));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const [code] = newCodes(),
    batch = await env.makeBatch([{ code, type: "MENSUAL", secundarias: 0 }]);
  writeFileSync(join(dir, "batch"), JSON.stringify(batch));
  for (const args of [
    ["sql-import", "--batch", join(dir, "batch"), "--public-key", env.glPublic],
    [
      "sql-transfer",
      "--license",
      "test-license",
      "--to-thumbprint",
      "A".repeat(43),
    ],
    ["sql-revoke", "--license", "test-license"],
    ["sql-revoke", "--code", code],
  ]) {
    execFileSync(
      process.execPath,
      ["src/cli.js", ...args, "--out", join(dir, "sql")],
      {
        env: {
          ...process.env,
          CODE_PEPPER: Buffer.from(env.pepper).toString("base64"),
        },
      },
    );
    const sql = readFileSync(join(dir, "sql"), "utf8");
    assert.doesNotMatch(sql, /\b(BEGIN|COMMIT)\b/i);
    db.exec(sql);
    db.exec(sql);
  }
  assert.equal(db.prepare("SELECT state FROM codes").get().state, "REVOKED");
});
