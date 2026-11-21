import { createHash, webcrypto } from "node:crypto";
const ec = { name: "ECDSA", namedCurve: "P-256" };
const sig = { name: "ECDSA", hash: "SHA-256" };
const transcript = (datos) => Buffer.from("SPVI-DIR1|" + datos);
export const maxBytes = 65536;
export const fingerprint = (der) =>
  "sha256:" +
  createHash("sha256").update(der).digest("hex").match(/.{4}/g).join(":");
export async function publicKey(spki) {
  const der = Buffer.from(spki, "base64");
  if (der.toString("base64") !== spki) throw Error("spki");
  return webcrypto.subtle.importKey("spki", der, ec, false, ["verify"]);
}
export async function validate(data) {
  const epoch = (n) => Number.isSafeInteger(n) && n >= 0;
  if (
    data.v !== 1 ||
    !epoch(data.seq) ||
    !epoch(data.emitidaEn) ||
    !Array.isArray(data.claves)
  )
    throw Error("cabecera");
  let current = 0;
  const seen = new Set();
  for (const key of data.claves) {
    if (
      typeof key.kid !== "string" ||
      !key.kid ||
      !["firma", "ecdh", "canje"].includes(key.uso) ||
      !["vigente", "retirada", "comprometida"].includes(key.estado) ||
      !epoch(key.desde) ||
      !(key.hasta === null || (epoch(key.hasta) && key.hasta >= key.desde))
    )
      throw Error("entrada");
    await publicKey(key.spki);
    if (key.huella !== fingerprint(Buffer.from(key.spki, "base64")))
      throw Error("huella");
    const identity = JSON.stringify([key.uso, key.spki]);
    if (seen.has(identity)) throw Error("entrada duplicada");
    seen.add(identity);
    if (key.uso === "ecdh" && key.estado === "vigente" && ++current > 1)
      throw Error("ecdh vigente duplicada");
  }
  return data;
}
export async function sign(data, key, raiz) {
  await validate(data);
  const datos = JSON.stringify(data);
  const firma = Buffer.from(
    await webcrypto.subtle.sign(sig, key, transcript(datos)),
  ).toString("base64url");
  const envelope = { datos, firma, raiz };
  if (Buffer.byteLength(JSON.stringify(envelope)) > maxBytes)
    throw Error("tamaño");
  return envelope;
}
export async function verify(text, roots, previous) {
  if (Buffer.byteLength(text) > maxBytes) throw Error("tamaño");
  const { datos, firma, raiz } = JSON.parse(text);
  if (
    typeof datos !== "string" ||
    !["A", "B"].includes(raiz) ||
    !/^[\w-]{86}$/.test(firma)
  )
    throw Error("sobre");
  const raw = Buffer.from(firma, "base64url");
  if (raw.toString("base64url") !== firma) throw Error("firma");
  let ok = false;
  for (const root of roots)
    if (
      await webcrypto.subtle.verify(
        sig,
        await publicKey(root),
        raw,
        transcript(datos),
      )
    ) {
      ok = true;
      break;
    }
  if (!ok) throw Error("firma");
  const data = await validate(JSON.parse(datos));
  if (
    previous &&
    (data.seq < JSON.parse(previous.datos).seq ||
      (data.seq === JSON.parse(previous.datos).seq && datos !== previous.datos))
  )
    throw Error("retroceso");
  return data;
}
