import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
  mkdtempSync,
  writeFileSync,
  readFileSync,
  statSync,
  rmSync,
} from "node:fs";
import { randomBytes } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
test("raíz de PRUEBA cifrada, bootstrap, rotación y verificación por CLI", (t) => {
  const cwd = mkdtempSync(join(tmpdir(), "gl-dir-"));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  const env = {
    ...process.env,
    DIR_PASSPHRASE: randomBytes(32).toString("base64"),
  };
  const cli = (...args) =>
    execFileSync(
      process.execPath,
      [fileURLToPath(new URL("../cli.js", import.meta.url)), ...args],
      { cwd, env, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
    );
  const root = cli("gen-root", "--out", "root.enc").trim().split(" ")[0];
  // Windows no aplica modos POSIX: un fichero normal reporta 0o666 aunque el
  // CLI pida 0o600. Solo tiene sentido comprobar el permiso en POSIX.
  if (process.platform !== "win32") {
    assert.equal(statSync(join(cwd, "root.enc")).mode & 0o777, 0o600);
  }
  cli("bootstrap-from-trust", "--out", "src.json");
  const v = JSON.parse(
    readFileSync(
      new URL("../../../docs/vectores/directorio-v1.json", import.meta.url),
    ),
  );
  writeFileSync(
    join(cwd, "entry"),
    JSON.stringify(JSON.parse(v.envelope.datos).claves[0]),
  );
  cli("add-key", "--entry", "entry", "--src", "src.json");
  const sign = (out) =>
    cli(
      "sign",
      "--root",
      "root.enc",
      "--src",
      "src.json",
      "--raiz",
      "B",
      "--out",
      out,
    );
  sign("first");
  sign("second");
  cli(
    "verify",
    "--root-pub",
    v.root,
    "--root-pub",
    root,
    "--previous",
    "first",
    "--file",
    "second",
  );
  assert.throws(() =>
    cli(
      "verify",
      "--root-pub",
      root,
      "--previous",
      "second",
      "--file",
      "first",
    ),
  );
  env.DIR_PASSPHRASE = randomBytes(32).toString("base64");
  assert.throws(() => sign("third"));
});
