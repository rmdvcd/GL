#!/usr/bin/env node
/**
 * Herramientas de operador. No forma parte del runtime HTTP.
 *
 * Nunca imprime codigos, claves privadas ni tokens. Los ficheros con claves o
 * lotes se escriben con permisos 0600 y estan ignorados por git.
 */

import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { parseArgs } from "node:util";
import { fromBase64 } from "./canonical.js";
import { createSignedBatch, verifyBatch } from "./batch.js";
import { JsonFileStore } from "./file-store.js";
import { formatCode, generateNormalizedCode, hashCode, normalizeCode } from "./code.js";
import {
  exportPkcs8B64,
  exportSpkiB64,
  generateP256KeyPair,
  importPkcs8B64,
  importSpkiB64,
} from "./keys.js";

const HELP = `Uso: node src/cli.js <comando> [opciones]

Comandos:
  gen-keys --role <gl|service> --out <fichero.json> [--force]
      Genera un par P-256. La privada queda en el fichero (0600).

  mint-batch --key <gl-key.json> --app <APP> --count <n> --type <tipo>
             [--secundarias 0] [--deadline ISO] [--out <lote.json>]
      Acuña un lote firmado por GL. Equivale al caso de uso Kotlin del emisor.

  verify-batch --batch <lote.json> [--public-key <spki-b64>]
               [--max-secundarias 0]
      Verifica estructura y firma; imprime solo el resumen.

  sql-import --batch <lote.json> [--public-key <...>] --out <sql>
             [--max-secundarias 0]
      Verifica el lote y emite SQL para D1 con HMAC de cada codigo.
      El pepper se lee de CODE_PEPPER (Base64).

  import-local --batch <lote.json> --store <store.json> [--public-key <...>]
                [--max-secundarias 0]
      Importa el lote en un almacen JSON para desarrollo. No usar en produccion.

  sql-revoke (--code <codigo> | --license <licenseId>) [--out <sql>]
  sql-transfer --license <licenseId> --to-thumbprint <thumb> [--out <sql>]

Variables:
  CODE_PEPPER          Base64 de >=32 bytes. Obligatorio en sql-import.
  GL_ISSUER_PUBLIC_KEY SPKI Base64 de la clave de firma de GL.
`;

function fail(message) {
  console.error(message);
  process.exit(1);
}

async function readJson(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

function sqlQuoted(value) {
  if (value === null || value === undefined) return "NULL";
  if (typeof value !== "string") throw new Error("sql-value");
  if (value.length > 512) throw new Error("sql-value");
  return `'${value.replace(/'/g, "''")}'`;
}

async function writeOut(path, content, mode = 0o600) {
  if (path) {
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, content, { mode });
  } else {
    process.stdout.write(content);
  }
}

async function commandGenKeys(values) {
  const role = values.role;
  if (role !== "gl" && role !== "service") fail("--role debe ser gl o service");
  if (!values.out) fail("--out es obligatorio para gen-keys");
  const keyPair = await generateP256KeyPair();
  const record = {
    v: 1,
    role,
    kid: role === "gl" ? "gl-sign-v1" : "gl-redeem-v1",
    publicKey: await exportSpkiB64(keyPair.publicKey),
    privateKey: await exportPkcs8B64(keyPair.privateKey),
    createdAt: new Date().toISOString(),
  };
  try {
    await writeFile(values.out, JSON.stringify(record, null, 2), { mode: 0o600, flag: values.force ? "w" : "wx" });
  } catch (error) {
    fail(`No se pudo escribir ${values.out}: ${error?.message ?? "error"}`);
  }
  console.log(`Par generado (${role}).`);
  console.log(`publicKey: ${record.publicKey}`);
  console.log("La clave privada NO se imprime. Guárdala en el gestor de secretos.");
}

async function resolveGlPublicKey(values) {
  const raw = values["public-key"] ?? process.env.GL_ISSUER_PUBLIC_KEY;
  if (!raw) fail("Falta --public-key o GL_ISSUER_PUBLIC_KEY");
  return importSpkiB64(raw.trim());
}

async function commandMintBatch(values) {
  if (!values.key) fail("--key es obligatorio");
  if (!values.out) fail("--out es obligatorio: el lote contiene codigos en claro");
  if (!values.app) fail("--app es obligatorio");
  const count = Number(values.count ?? "1");
  if (!Number.isInteger(count) || count < 1 || count > 5000) fail("--count fuera de rango");
  const type = values.type ?? "MENSUAL";
  if (!["MENSUAL", "SEMESTRAL", "ANUAL", "PERPETUA"].includes(type)) fail("--type invalido");
  const secundarias = Number(values.secundarias ?? "0");
  if (!Number.isInteger(secundarias) || secundarias < 0 || secundarias > 10) {
    fail("--secundarias fuera de rango");
  }
  const keyRecord = await readJson(values.key);
  if (keyRecord.role !== "gl") fail("--key no es una clave de emisor GL");
  const privateKey = await importPkcs8B64(keyRecord.privateKey);
  const codes = new Set();
  while (codes.size < count) codes.add(generateNormalizedCode());
  const batch = await createSignedBatch({
    signingPrivateKey: privateKey,
    issuerKid: keyRecord.kid ?? "gl-sign-v1",
    appId: values.app,
    batchId: values["batch-id"] ?? crypto.randomUUID(),
    createdAt: new Date().toISOString(),
    entries: [...codes].map((code) => ({
      code: formatCode(code),
      type,
      secundarias,
      ...(values.deadline ? { redeemBefore: values.deadline } : {}),
    })),
  });
  await writeOut(values.out, JSON.stringify(batch, null, 2));
  console.log(`Lote ${batch.batchId}: ${batch.entries.length} codigos para ${batch.appId} (${type}).`);
}

function maxSecundariasOption(values) {
  const max = Number(values["max-secundarias"] ?? "0");
  if (!Number.isInteger(max) || max < 0 || max > 10) {
    fail("--max-secundarias debe ser un entero entre 0 y 10");
  }
  return max;
}

async function commandVerifyBatch(values) {
  if (!values.batch) fail("--batch es obligatorio");
  const batch = await readJson(values.batch);
  const verified = await verifyBatch(batch, {
    glPublicKey: await resolveGlPublicKey(values),
    maxSecundarias: maxSecundariasOption(values),
  });
  console.log(
    JSON.stringify(
      {
        batchId: verified.batchId,
        appId: verified.appId,
        entries: verified.entries.length,
        createdAt: verified.createdAt,
      },
      null,
      2,
    ),
  );
}

async function commandSqlImport(values) {
  if (!values.batch) fail("--batch es obligatorio");
  if (!values.out) fail("--out es obligatorio");
  const pepperRaw = process.env.CODE_PEPPER;
  if (!pepperRaw) fail("CODE_PEPPER no esta definido");
  const pepper = fromBase64(pepperRaw);
  if (pepper.length < 32) fail("CODE_PEPPER debe tener >=32 bytes");
  const batch = await readJson(values.batch);
  const verified = await verifyBatch(batch, {
    glPublicKey: await resolveGlPublicKey(values),
    maxSecundarias: maxSecundariasOption(values),
  });
  const lines = ["BEGIN;"];
  for (const entry of verified.entries) {
    const codeHash = await hashCode(entry.code, pepper);
    lines.push(
      `INSERT INTO codes (code_hash, app_id, type, secundarias, batch_id, redeem_before, state, created_at) VALUES (` +
        `${sqlQuoted(codeHash)}, ${sqlQuoted(verified.appId)}, ${sqlQuoted(entry.type)}, ` +
        `${entry.secundarias}, ${sqlQuoted(verified.batchId)}, ${sqlQuoted(entry.redeemBefore)}, ` +
        `'UNUSED', ${sqlQuoted(verified.createdAt)});`,
    );
  }
  lines.push("COMMIT;", "");
  await writeOut(values.out, lines.join("\n"), 0o600);
  if (values.out) {
    console.log(`SQL generado para ${verified.entries.length} codigos.`);
    console.log("Ejecuta: wrangler d1 execute <DB> --remote --file <sql>");
    console.log("Borra el SQL despues: contiene hashes, no codigos, pero es material operativo.");
  }
}

async function commandImportLocal(values) {
  if (!values.batch) fail("--batch es obligatorio");
  if (!values.store) fail("--store es obligatorio");
  const pepperRaw = process.env.CODE_PEPPER;
  if (!pepperRaw) fail("CODE_PEPPER no esta definido");
  const pepper = fromBase64(pepperRaw);
  if (pepper.length < 32) fail("CODE_PEPPER debe tener >=32 bytes");
  const batch = await readJson(values.batch);
  const verified = await verifyBatch(batch, {
    glPublicKey: await resolveGlPublicKey(values),
    maxSecundarias: maxSecundariasOption(values),
  });
  const store = new JsonFileStore(values.store);
  await store.load();
  const entries = [];
  for (const entry of verified.entries) {
    entries.push({
      codeHash: await hashCode(entry.code, pepper),
      type: entry.type,
      secundarias: entry.secundarias,
      redeemBefore: entry.redeemBefore,
    });
  }
  const result = await store.importCodes(entries, verified);
  if (result.conflicts.length > 0) fail("El lote entra en conflicto con codigos ya importados");
  console.log(`Importados ${result.imported}; ya existian ${result.skipped}.`);
}

async function commandSqlStatus(values, mode) {
  if (values.code) {
    if (mode !== "revoke" || values.license) fail("Indica solo --code o --license");
    const pepper = fromBase64(process.env.CODE_PEPPER ?? "");
    if (pepper.length < 32) fail("CODE_PEPPER inválido");
    const hash = sqlQuoted(await hashCode(normalizeCode(values.code), pepper));
    await writeOut(values.out, `UPDATE codes SET state='REVOKED' WHERE code_hash=${hash};\nUPDATE redemptions SET active=0,status='REVOCADA' WHERE code_hash=${hash} AND active=1;\nDELETE FROM transfers WHERE code_hash=${hash};\n`, 0o600);
    return;
  }
  if (!values.license) fail("--license es obligatorio");
  if (!/^[A-Za-z0-9._:-]{8,64}$/.test(values.license)) fail("licenseId invalido");
  if (mode === "transfer" && !/^[A-Za-z0-9_-]{43}$/.test(values["to-thumbprint"] ?? "")) {
    fail("--to-thumbprint debe ser el thumbprint de 43 caracteres de la nueva instalacion");
  }
  const license = sqlQuoted(values.license);
  const sql =
    `BEGIN;\n` +
    (mode === "revoke"
      ? `UPDATE redemptions SET status = 'REVOCADA', active = 0 ` +
        `WHERE license_id = ${license} AND active = 1;\n` +
        `UPDATE codes SET state = 'REVOKED' WHERE code_hash = ` +
        `(SELECT code_hash FROM redemptions WHERE license_id = ${license});\n` +
        `DELETE FROM transfers WHERE code_hash = ` +
        `(SELECT code_hash FROM redemptions WHERE license_id = ${license});\n`
      : `INSERT INTO transfers (code_hash, from_license_id, to_thumbprint, created_at)\n` +
        `  SELECT r.code_hash, ${license}, ${sqlQuoted(values["to-thumbprint"] ?? "")}, ` +
        `strftime('%Y-%m-%dT%H:%M:%fZ','now')\n` +
        `  FROM redemptions r\n` +
        `  WHERE r.license_id = ${license}\n` +
        `    AND (r.active = 1 OR EXISTS (\n` +
        `      SELECT 1 FROM transfers t WHERE t.code_hash = r.code_hash\n` +
        `        AND t.from_license_id = r.license_id))\n` +
        `  ON CONFLICT(code_hash) DO UPDATE SET\n` +
        `    to_thumbprint = excluded.to_thumbprint\n` +
        `  WHERE transfers.from_license_id = excluded.from_license_id;\n` +
        `UPDATE redemptions SET status = 'REVOCADA', active = 0 ` +
        `WHERE license_id = ${license} AND active = 1;\n` +
        `-- solo la instalacion indicada puede canjear; hereda el vencimiento restante\n`) +
    `COMMIT;\n`;
  await writeOut(values.out, sql, 0o600);
  if (values.out) console.log(`SQL de ${mode === "revoke" ? "revocacion" : "transferencia"} generado.`);
}

async function main() {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      role: { type: "string" },
      out: { type: "string" },
      force: { type: "boolean", default: false },
      key: { type: "string" },
      app: { type: "string" },
      count: { type: "string" },
      type: { type: "string" },
      secundarias: { type: "string" },
      deadline: { type: "string" },
      "batch-id": { type: "string" },
      batch: { type: "string" },
      "public-key": { type: "string" },
      code: { type: "string" },
      license: { type: "string" },
      store: { type: "string" },
      "to-thumbprint": { type: "string" },
      "max-secundarias": { type: "string" },
      help: { type: "boolean", short: "h", default: false },
    },
  });
  const command = positionals[0];
  if (!command || values.help) {
    console.log(HELP);
    return;
  }
  switch (command) {
    case "gen-keys":
      return commandGenKeys(values);
    case "mint-batch":
      return commandMintBatch(values);
    case "verify-batch":
      return commandVerifyBatch(values);
    case "sql-import":
      return commandSqlImport(values);
    case "import-local":
      return commandImportLocal(values);
    case "sql-revoke":
      return commandSqlStatus(values, "revoke");
    case "sql-transfer":
      return commandSqlStatus(values, "transfer");
    default:
      fail(`Comando desconocido: ${command}`);
  }
}

main().catch((error) => {
  console.error(`Error: ${error?.message ?? "desconocido"}`);
  process.exit(1);
});

