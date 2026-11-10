/**
 * Permiso firmado de una app secundaria (sub-token).
 *
 * La licencia primaria la firma el servicio (`gl-redeem-v1`). El permiso lo
 * firma la clave P-256 de la instalacion primaria, cuya publica viaja dentro de
 * la licencia primaria (`devicePublicKey`). Asi una secundaria puede activarse
 * sin conexion y sin que el servicio conozca su existencia.
 *
 * Texto firmado (UTF-8, separado por "|"):
 *   GL-REDEEM-SUB1|<licenseId>|<thumbprintSecundaria>|<slot>|<allowed>|<venceEn|PERPETUA>
 *
 * `allowed` se copia de `secundarias` de la licencia primaria y `venceEn` de su
 * `expiresAt`; la secundaria exige que coincidan, de modo que un permiso no
 * puede ampliar la cuota ni el plazo por si solo.
 */

import { fromBase64, toBase64 } from "./canonical.js";
import { importSpkiB64, keyThumbprint, signDer, verifyDer } from "./keys.js";

export const SUBTOKEN_VERSION = 1;
export const SUBTOKEN_ALG = "GL-REDEEM-SUB-P256-SHA256-v1";
const SUBTOKEN_DOMAIN = "GL-REDEEM-SUB1";

const THUMBPRINT_RE = /^[A-Za-z0-9_-]{43}$/;
const LICENSE_ID_RE = /^[A-Za-z0-9._:-]{8,64}$/;
const ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/;

export function subTokenSigningBytes(token) {
  return new TextEncoder().encode(
    [
      SUBTOKEN_DOMAIN,
      token.licenseId,
      token.deviceThumbprint,
      String(token.slot),
      String(token.allowed),
      token.expiresAt ?? "PERPETUA",
    ].join("|"),
  );
}

function validateFields(token) {
  if (!token || typeof token !== "object") throw new Error("subtoken-error:shape");
  if (token.v !== SUBTOKEN_VERSION) throw new Error("subtoken-error:version");
  if (token.alg !== SUBTOKEN_ALG) throw new Error("subtoken-error:alg");
  if (typeof token.licenseId !== "string" || !LICENSE_ID_RE.test(token.licenseId)) {
    throw new Error("subtoken-error:license-id");
  }
  if (typeof token.deviceThumbprint !== "string" || !THUMBPRINT_RE.test(token.deviceThumbprint)) {
    throw new Error("subtoken-error:device");
  }
  if (!Number.isInteger(token.slot) || token.slot < 1 || token.slot > 10) {
    throw new Error("subtoken-error:slot");
  }
  if (!Number.isInteger(token.allowed) || token.allowed < 1 || token.allowed > 10) {
    throw new Error("subtoken-error:allowed");
  }
  if (token.slot > token.allowed) throw new Error("subtoken-error:slot");
  if (token.expiresAt !== "PERPETUA" && (!ISO_RE.test(token.expiresAt ?? ""))) {
    throw new Error("subtoken-error:expires");
  }
  if (typeof token.sig !== "string" || token.sig.length === 0) {
    throw new Error("subtoken-error:sig");
  }
}

/**
 * Firma un permiso para una instalacion secundaria. `primaryPayload` es el
 * payload ya verificado de la licencia primaria.
 */
export async function createSubToken(options) {
  const { primaryPayload, secondaryThumbprint, slot, signingPrivateKey } = options;
  if (!primaryPayload || primaryPayload.secundarias < 1) {
    throw new Error("subtoken-error:not-allowed");
  }
  if (primaryPayload.status === "REVOCADA" || primaryPayload.status === "VENCIDA") {
    throw new Error("subtoken-error:license-inactive");
  }
  if (!THUMBPRINT_RE.test(secondaryThumbprint ?? "")) {
    throw new Error("subtoken-error:device");
  }
  if (!Number.isInteger(slot) || slot < 1 || slot > primaryPayload.secundarias) {
    throw new Error("subtoken-error:slot");
  }
  const unsigned = {
    v: SUBTOKEN_VERSION,
    alg: SUBTOKEN_ALG,
    licenseId: primaryPayload.licenseId,
    deviceThumbprint: secondaryThumbprint,
    slot,
    allowed: primaryPayload.secundarias,
    expiresAt: primaryPayload.expiresAt ?? "PERPETUA",
    sig: "",
  };
  const signature = await signDer(signingPrivateKey, subTokenSigningBytes(unsigned));
  return { ...unsigned, sig: toBase64(signature) };
}

/**
 * Verifica el permiso contra la licencia primaria y el thumbprint de esta
 * instalacion. Lanza `subtoken-error:<motivo>` si algo no cuadra.
 */
export async function verifySubToken(
  token,
  primaryPayload,
  secondaryThumbprint,
  nowMs = Date.now(),
  options = {},
) {
  validateFields(token);
  if (!primaryPayload || primaryPayload.licenseId !== token.licenseId) {
    throw new Error("subtoken-error:license-id");
  }
  if (!Number.isInteger(primaryPayload.secundarias) || primaryPayload.secundarias !== token.allowed) {
    throw new Error("subtoken-error:allowed");
  }
  const expectedExpiry = primaryPayload.expiresAt ?? "PERPETUA";
  if (token.expiresAt !== expectedExpiry) throw new Error("subtoken-error:expires");
  if (token.deviceThumbprint !== secondaryThumbprint) {
    throw new Error("subtoken-error:device");
  }
  if (primaryPayload.status === "REVOCADA" || primaryPayload.status === "VENCIDA") {
    throw new Error("subtoken-error:license-inactive");
  }
  if (token.expiresAt !== "PERPETUA" && !options.allowExpired) {
    const expires = Date.parse(token.expiresAt);
    if (!Number.isFinite(expires) || expires <= nowMs) throw new Error("subtoken-error:expired");
  }
  if (typeof primaryPayload.devicePublicKey !== "string") {
    throw new Error("subtoken-error:missing-primary-key");
  }
  const spki = fromBase64(primaryPayload.devicePublicKey);
  if ((await keyThumbprint(spki)) !== primaryPayload.deviceThumbprint) {
    throw new Error("subtoken-error:primary-key");
  }
  const publicKey = await importSpkiB64(primaryPayload.devicePublicKey);
  const ok = await verifyDer(publicKey, subTokenSigningBytes(token), fromBase64(token.sig));
  if (!ok) throw new Error("subtoken-error:signature");
  return token;
}
