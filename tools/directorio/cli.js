#!/usr/bin/env node
import { readFile, writeFile, rename, unlink, open } from "node:fs/promises";
import {
  scryptSync,
  randomBytes,
  createCipheriv,
  createDecipheriv,
  webcrypto,
} from "node:crypto";
import { createInterface } from "node:readline/promises";
import { Writable } from "node:stream";
import { resolve } from "node:path";
import { parseArgs } from "node:util";
import {
  fingerprint,
  publicKey,
  sign,
  verify,
  validate,
} from "./directory.mjs";
const [command, ...args] = process.argv.slice(2);
const names =
  "out src kid uso spki desde hasta estado root raiz file previous entry".split(
    " ",
  );
const { values: o } = parseArgs({
  args,
  options: {
    ...Object.fromEntries(names.map((n) => [n, { type: "string" }])),
    "root-pub": { type: "string", multiple: true },
  },
});
const read = async (p) => JSON.parse(await readFile(p, "utf8"));
const save = async (p, data) => {
  const temp = p + "." + randomBytes(8).toString("hex");
  try {
    await writeFile(temp, JSON.stringify(data) + "\n", {
      flag: "wx",
      mode: 0o600,
    });
    await rename(temp, p);
  } finally {
    await unlink(temp).catch(() => {});
  }
};
const ec = { name: "ECDSA", namedCurve: "P-256" };
async function derive(salt) {
  let pass = process.env.DIR_PASSPHRASE;
  if (!pass && process.stdin.isTTY) {
    const silent = new Writable({
      write(_chunk, _encoding, done) {
        done();
      },
    });
    const prompt = createInterface({
      input: process.stdin,
      output: silent,
      terminal: true,
      historySize: 0,
    });
    process.stderr.write("Passphrase de la raíz (sin eco): ");
    try {
      pass = await prompt.question("");
    } finally {
      prompt.close();
      process.stderr.write("\n");
    }
  }
  if (!pass) throw Error("Falta DIR_PASSPHRASE");
  return scryptSync(pass, salt, 32, {
    N: 2 ** 17,
    r: 8,
    p: 1,
    maxmem: 256 * 1024 ** 2,
  });
}
const b64 = (b) => Buffer.from(b).toString("base64");
const decode = (s) => Buffer.from(s, "base64");
async function main() {
  switch (command) {
    case "gen-root": {
      const pair = await webcrypto.subtle.generateKey(ec, true, [
        "sign",
        "verify",
      ]);
      const salt = randomBytes(16),
        iv = randomBytes(12);
      const cipher = createCipheriv("aes-256-gcm", await derive(salt), iv);
      const encrypted = Buffer.concat([
        cipher.update(
          Buffer.from(
            await webcrypto.subtle.exportKey("pkcs8", pair.privateKey),
          ),
        ),
        cipher.final(),
      ]);
      await writeFile(
        o.out,
        JSON.stringify({
          v: 1,
          salt: b64(salt),
          iv: b64(iv),
          encrypted: b64(encrypted),
          tag: b64(cipher.getAuthTag()),
        }),
        { flag: "wx", mode: 0o600 },
      );
      const der = Buffer.from(
        await webcrypto.subtle.exportKey("spki", pair.publicKey),
      );
      console.log(b64(der), fingerprint(der));
      break;
    }
    case "bootstrap-from-trust": {
      const data = {
        v: 1,
        seq: 0,
        emitidaEn: 0,
        claves: o.entry ? await read(o.entry) : [],
      };
      await validate(data);
      await writeFile(o.out, JSON.stringify(data), { flag: "wx", mode: 0o600 });
      break;
    }
    case "sign":
    case "add-key": {
      if (
        command === "sign" &&
        new Set([o.src, o.root, o.out].map((p) => resolve(p))).size !== 3
      )
        throw Error("Rutas superpuestas");
      const lock = await open(o.src + ".lock", "wx", 0o600);
      try {
        const data = await read(o.src);
        if (command === "add-key") {
          const key = o.entry
            ? await read(o.entry)
            : {
                kid: o.kid,
                uso: o.uso,
                spki: o.spki,
                desde: Number(o.desde),
                hasta: o.hasta ? Number(o.hasta) : null,
                estado: o.estado ?? "vigente",
                huella: fingerprint(decode(o.spki)),
              };
          await publicKey(key.spki);
          data.claves = data.claves.filter(
            (k) => k.uso !== key.uso || k.spki !== key.spki,
          );
          data.claves.push(key);
          await validate(data);
          await save(o.src, data);
          break;
        }
        if (!["A", "B"].includes(o.raiz) || !o.out || !data.claves.length)
          throw Error("Falta raíz, salida o entradas");
        const enc = await read(o.root);
        const decipher = createDecipheriv(
          "aes-256-gcm",
          await derive(decode(enc.salt)),
          decode(enc.iv),
        );
        decipher.setAuthTag(decode(enc.tag));
        const der = Buffer.concat([
          decipher.update(decode(enc.encrypted)),
          decipher.final(),
        ]);
        const key = await webcrypto.subtle.importKey("pkcs8", der, ec, false, [
          "sign",
        ]);
        der.fill(0);
        data.seq++;
        data.emitidaEn = Math.floor(Date.now() / 1000);
        const envelope = await sign(data, key, o.raiz);
        await save(o.src, data);
        await save(o.out, envelope);
      } finally {
        await lock.close();
        await unlink(o.src + ".lock");
      }
      break;
    }
    case "verify": {
      const roots = o["root-pub"] ?? [];
      const prev = o.previous ? await readFile(o.previous, "utf8") : null;
      if (prev) await verify(prev, roots);
      const data = await verify(
        await readFile(o.file, "utf8"),
        roots,
        prev ? JSON.parse(prev) : null,
      );
      console.log(`Directorio válido: seq=${data.seq}`);
      break;
    }
    default:
      throw Error("Comando desconocido");
  }
}
main().catch(() => {
  console.error(
    "Operación rechazada: comprueba archivos, campos, claves y passphrase.",
  );
  process.exitCode = 1;
});
