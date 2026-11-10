/**
 * Claves y firmas ECDSA P-256.
 *
 * WebCrypto entrega y espera firmas ECDSA en formato IEEE P1363 (r||s, 64 B).
 * Java/Android (`SHA256withECDSA`) y GL usan ASN.1 DER. Todo lo que viaja por
 * el contrato va en DER; la conversion ocurre en el borde.
 */

import { concatBytes, fromBase64, sha256, toBase64, toBase64Url } from "./canonical.js";

const CURVE = { name: "ECDSA", namedCurve: "P-256" };
const SIGN = { name: "ECDSA", hash: "SHA-256" };
const RAW_SIGNATURE_BYTES = 64;

function derLength(length) {
  if (length < 0x80) return Uint8Array.of(length);
  const bytes = [];
  let value = length;
  while (value > 0) {
    bytes.unshift(value & 0xff);
    value >>= 8;
  }
  if (bytes.length > 3) throw new Error("der-length-too-long");
  return Uint8Array.of(0x80 | bytes.length, ...bytes);
}

function derInteger(raw) {
  let start = 0;
  while (start < raw.length - 1 && raw[start] === 0) start += 1;
  let value = raw.subarray(start);
  if (value[0] & 0x80) value = concatBytes(Uint8Array.of(0), value);
  return concatBytes(Uint8Array.of(0x02), derLength(value.length), value);
}

/** r||s (64 B) -> SEQUENCE(INTEGER r, INTEGER s). */
export function rawSignatureToDer(raw) {
  if (raw.length !== RAW_SIGNATURE_BYTES) throw new Error("invalid-raw-signature");
  const r = derInteger(raw.subarray(0, 32));
  const s = derInteger(raw.subarray(32, 64));
  const body = concatBytes(r, s);
  return concatBytes(Uint8Array.of(0x30), derLength(body.length), body);
}

function readLength(der, offset) {
  if (offset >= der.length) throw new Error("invalid-der");
  const first = der[offset];
  if (first < 0x80) return { length: first, offset: offset + 1 };
  const count = first & 0x7f;
  if (count === 0 || count > 3 || offset + 1 + count > der.length) throw new Error("invalid-der");
  let length = 0;
  for (let i = 0; i < count; i += 1) length = (length << 8) | der[offset + 1 + i];
  if (length < 0x80) throw new Error("invalid-der");
  return { length, offset: offset + 1 + count };
}

function readInteger(der, offset) {
  if (der[offset] !== 0x02) throw new Error("invalid-der");
  const { length, offset: dataOffset } = readLength(der, offset + 1);
  const end = dataOffset + length;
  if (length === 0 || end > der.length) throw new Error("invalid-der");
  const value = der.subarray(dataOffset, end);
  if (value.length > 33) throw new Error("invalid-der");
  if (value.length > 1 && value[0] === 0 && (value[1] & 0x80) === 0) throw new Error("invalid-der");
  if (value[0] & 0x80) throw new Error("invalid-der");
  return { value, offset: end };
}

/** DER -> r||s (64 B) con ceros a la izquierda. */
export function derSignatureToRaw(der) {
  if (!(der instanceof Uint8Array) || der.length < 8 || der.length > 80) {
    throw new Error("invalid-der-signature");
  }
  if (der[0] !== 0x30) throw new Error("invalid-der-signature");
  const { length, offset } = readLength(der, 1);
  if (offset + length !== der.length) throw new Error("invalid-der-signature");
  const r = readInteger(der, offset);
  const s = readInteger(der, r.offset);
  if (s.offset !== der.length) throw new Error("invalid-der-signature");
  const out = new Uint8Array(RAW_SIGNATURE_BYTES);
  out.set(rightAlign(r.value, 32), 0);
  out.set(rightAlign(s.value, 32), 32);
  return out;
}

function rightAlign(value, size) {
  if (value.length === size) return value;
  if (value.length > size) {
    const extra = value.length - size;
    for (let i = 0; i < extra; i += 1) if (value[i] !== 0) throw new Error("invalid-der");
    return value.subarray(extra);
  }
  const out = new Uint8Array(size);
  out.set(value, size - value.length);
  return out;
}

export async function generateP256KeyPair(extractable = true) {
  return crypto.subtle.generateKey(CURVE, extractable, ["sign", "verify"]);
}

export async function exportSpkiB64(publicKey) {
  return toBase64(new Uint8Array(await crypto.subtle.exportKey("spki", publicKey)));
}

export async function exportPkcs8B64(privateKey) {
  return toBase64(new Uint8Array(await crypto.subtle.exportKey("pkcs8", privateKey)));
}

export async function importSpkiB64(spkiB64) {
  const bytes = fromBase64(spkiB64);
  // 91 B es la longitud de un SPKI P-256 sin comprimir; el limite evita
  // procesar blobs arbitrarios antes de que WebCrypto valide la curva.
  if (bytes.length < 60 || bytes.length > 200) throw new Error("invalid-spki");
  return crypto.subtle.importKey("spki", bytes, CURVE, true, ["verify"]);
}

export async function importPkcs8B64(pkcs8B64) {
  const bytes = fromBase64(pkcs8B64);
  if (bytes.length < 60 || bytes.length > 300) throw new Error("invalid-pkcs8");
  return crypto.subtle.importKey("pkcs8", bytes, CURVE, false, ["sign"]);
}

export async function signRaw(privateKey, bytes) {
  return new Uint8Array(await crypto.subtle.sign(SIGN, privateKey, bytes));
}

export async function signDer(privateKey, bytes) {
  return rawSignatureToDer(await signRaw(privateKey, bytes));
}

export async function verifyRaw(publicKey, bytes, rawSignature) {
  if (rawSignature.length !== RAW_SIGNATURE_BYTES) return false;
  try {
    return await crypto.subtle.verify(SIGN, publicKey, rawSignature, bytes);
  } catch {
    return false;
  }
}

/** Verifica una firma DER. Nunca lanza por firma malformada. */
export async function verifyDer(publicKey, bytes, derSignature) {
  let raw;
  try {
    raw = derSignatureToRaw(derSignature);
  } catch {
    return false;
  }
  return verifyRaw(publicKey, bytes, raw);
}

/** Identidad de instalacion: SHA-256 del SPKI, en Base64url sin relleno. */
export async function keyThumbprint(spkiBytes) {
  return toBase64Url(await sha256(spkiBytes));
}
