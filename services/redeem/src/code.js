/**
 * Codigos de 16 caracteres.
 *
 * La validez de formato NO es prueba de licencia: solo normaliza y evita que
 * cadenas absurdas lleguen al almacen. La autoridad es la firma del lote GL y
 * el canje atomico del servicio.
 */

import { utf8 } from "./canonical.js";
import { hmacSha256 } from "./canonical.js";
import { toBase64Url } from "./canonical.js";

/** Mismo alfabeto que `LicenseCodeGenerator`: 34 simbolos, sin I ni O. */
export const CODE_ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
export const CODE_LENGTH = 16;
export const CODE_GROUPED_LENGTH = 19;

const NORMALIZED_RE = /^[0-9A-HJ-NP-Z]{16}$/;
const RAW_MAX_LENGTH = 64;

export function normalizeCode(input) {
  if (typeof input !== "string" || input.length > RAW_MAX_LENGTH) {
    throw new Error("invalid-code");
  }
  const normalized = input.replace(/[\s-]/g, "").toUpperCase();
  if (!NORMALIZED_RE.test(normalized)) throw new Error("invalid-code");
  return normalized;
}

export function isValidCode(input) {
  try {
    normalizeCode(input);
    return true;
  } catch {
    return false;
  }
}

export function formatCode(normalized) {
  if (!NORMALIZED_RE.test(normalized)) throw new Error("invalid-code");
  return normalized.match(/.{4}/g).join("-");
}

/**
 * Identificador de busqueda del codigo. El servicio nunca guarda el codigo en
 * claro: guarda HMAC-SHA256(pepper, "GL-CODE1|" + codigo normalizado).
 */
export async function hashCode(normalized, pepperBytes) {
  if (!NORMALIZED_RE.test(normalized)) throw new Error("invalid-code");
  if (!(pepperBytes instanceof Uint8Array) || pepperBytes.length < 16) {
    throw new Error("invalid-pepper");
  }
  const digest = await hmacSha256(pepperBytes, utf8(`GL-CODE1|${normalized}`));
  return toBase64Url(digest);
}

/** Muestreo por rechazo: 256 no es multiplo de 34, asi que `% 34` sesgaria. */
const REJECTION_LIMIT = Math.floor(256 / CODE_ALPHABET.length) * CODE_ALPHABET.length;

export function bytesToCode(randomBytes) {
  if (!(randomBytes instanceof Uint8Array)) throw new Error("invalid-random");
  let out = "";
  for (const byte of randomBytes) {
    if (byte >= REJECTION_LIMIT) continue;
    out += CODE_ALPHABET[byte % CODE_ALPHABET.length];
    if (out.length === CODE_LENGTH) return out;
  }
  throw new Error("not-enough-random");
}

export function generateNormalizedCode(webCrypto = crypto) {
  const bytes = new Uint8Array(64);
  for (let attempt = 0; attempt < 8; attempt += 1) {
    webCrypto.getRandomValues(bytes);
    try {
      return bytesToCode(bytes);
    } catch {
      // Repetir con entropia nueva; la probabilidad de fallar 8 veces es despreciable.
    }
  }
  throw new Error("random-failure");
}
