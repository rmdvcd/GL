/**
 * Canonicalizacion y utilidades de bytes compartidas por servicio y cliente.
 *
 * El contrato reutiliza la misma construccion que GL (`CryptoEngine.canonical`):
 * cada campo UTF-8 va precedido por su longitud en 4 bytes big-endian. No se
 * usan delimitadores `|`, que permitirian colisiones entre campos.
 */

const te = new TextEncoder();

export function canonical(parts) {
  if (!Array.isArray(parts)) throw new TypeError("canonical expects an array");
  let total = 0;
  const encoded = parts.map((part) => {
    if (typeof part !== "string") throw new TypeError("canonical fields must be strings");
    const bytes = te.encode(part);
    total += 4 + bytes.length;
    return bytes;
  });
  const out = new Uint8Array(total);
  const view = new DataView(out.buffer);
  let offset = 0;
  for (const bytes of encoded) {
    view.setUint32(offset, bytes.length, false);
    offset += 4;
    out.set(bytes, offset);
    offset += bytes.length;
  }
  return out;
}

export function concatBytes(...chunks) {
  let total = 0;
  for (const chunk of chunks) total += chunk.length;
  const out = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

export function toBase64(bytes) {
  let binary = "";
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

export function fromBase64(text) {
  const normalized = String(text).replace(/\s+/g, "");
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(normalized)) throw new Error("invalid-base64");
  const binary = atob(normalized);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i);
  return out;
}

export function toBase64Url(bytes) {
  return toBase64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

export function fromBase64Url(text) {
  const normalized = String(text).replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  return fromBase64(padded);
}

export function utf8(text) {
  return te.encode(String(text));
}

export function hex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

export async function sha256(bytes) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

export async function hmacSha256(keyBytes, messageBytes) {
  const key = await crypto.subtle.importKey(
    "raw",
    keyBytes,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, messageBytes));
}

/** Comparacion en tiempo constante para valores derivados (no para secretos de usuario). */
export function bytesEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a[i] ^ b[i];
  return diff === 0;
}
