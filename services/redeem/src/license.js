/**
 * Licencia firmada por el servicio de canje.
 *
 * No contiene datos personales, ni el codigo canjeado, ni claves. La identidad
 * del dispositivo es el thumbprint SHA-256 de su SPKI P-256. El cliente verifica
 * la firma con la clave publica del servicio fijada en el build.
 */

import { canonical, fromBase64, toBase64, toBase64Url } from "./canonical.js";
import { signDer, verifyDer } from "./keys.js";

export const LICENSE_VERSION = 1;
export const LICENSE_ALG = "GL-REDEEM-LICENSE-P256-SHA256-v1";
export const STATUS_ALG = "GL-REDEEM-STATUS-P256-SHA256-v1";
export const STATUS_DOC_VERSION = 1;
const LICENSE_DOMAIN = "GL-REDEEM-LICENSE1";
const STATUS_DOMAIN = "GL-REDEEM-STATUS1";

export const LICENSE_TYPES = Object.freeze({
  MENSUAL: 30,
  SEMESTRAL: 180,
  ANUAL: 365,
  PERPETUA: null,
});

export const LICENSE_STATUSES = Object.freeze(["ACTIVA", "VENCIDA", "REVOCADA", "PERPETUA"]);

export function isValidType(type) {
  return Object.prototype.hasOwnProperty.call(LICENSE_TYPES, type);
}

export function isValidStatus(status) {
  return LICENSE_STATUSES.includes(status);
}

export function licenseSigningBytes(payload) {
  return canonical([
    LICENSE_DOMAIN,
    String(payload.v),
    payload.licenseId,
    payload.appId,
    payload.type,
    payload.status,
    payload.issuedAt,
    payload.expiresAt ?? "",
    payload.deviceThumbprint,
    payload.redemptionId,
    payload.transferredFrom ?? "",
    String(payload.secundarias),
    payload.devicePublicKey ?? "",
  ]);
}

export function statusSigningBytes(doc) {
  return canonical([
    STATUS_DOMAIN,
    String(doc.v),
    doc.licenseId,
    doc.appId,
    doc.type,
    doc.status,
    doc.issuedAt,
    doc.expiresAt ?? "",
    doc.deviceThumbprint,
    doc.redemptionId,
    doc.transferredFrom ?? "",
    String(doc.secundarias),
    doc.devicePublicKey ?? "",
    doc.checkedAt,
  ]);
}

export function licensePayloadFromRow(row, nowMs, statusOverride) {
  const status = statusOverride ?? effectiveStatus(row, nowMs);
  return {
    v: LICENSE_VERSION,
    licenseId: row.licenseId,
    appId: row.appId,
    type: row.type,
    status,
    issuedAt: row.issuedAt,
    expiresAt: row.expiresAt ?? null,
    deviceThumbprint: row.deviceThumbprint,
    redemptionId: row.redemptionId,
    transferredFrom: row.transferredFrom ?? null,
    secundarias: row.secundarias ?? 0,
    devicePublicKey: row.devicePublicKey ?? null,
  };
}

export function effectiveStatus(row, nowMs) {
  if (row.status === "REVOCADA") return "REVOCADA";
  if (row.type === "PERPETUA" || row.expiresAt === null) return "PERPETUA";
  const expires = Date.parse(row.expiresAt);
  if (!Number.isFinite(expires)) return "REVOCADA";
  return expires <= nowMs ? "VENCIDA" : "ACTIVA";
}

export async function signLicenseEnvelope(payload, servicePrivateKey, kid = "gl-redeem-v1") {
  const signature = await signDer(servicePrivateKey, licenseSigningBytes(payload));
  return {
    v: 1,
    alg: LICENSE_ALG,
    kid,
    license: payload,
    sig: toBase64(signature),
  };
}

/** Lanza si el sobre o la firma no son validos. Devuelve el payload verificado. */
export async function verifyLicenseEnvelope(envelope, servicePublicKey) {
  if (!envelope || typeof envelope !== "object") throw new Error("invalid-license");
  if (envelope.v !== 1 || envelope.alg !== LICENSE_ALG) throw new Error("invalid-license");
  if (typeof envelope.kid !== "string" || envelope.kid.length === 0) {
    throw new Error("invalid-license");
  }
  const payload = envelope.license;
  validateLicensePayload(payload);
  const signature = fromBase64(envelope.sig);
  const ok = await verifyDer(servicePublicKey, licenseSigningBytes(payload), signature);
  if (!ok) throw new Error("invalid-license-signature");
  return payload;
}

export async function signStatusDoc(payload, servicePrivateKey, kid = "gl-redeem-v1") {
  const signature = await signDer(servicePrivateKey, statusSigningBytes(payload));
  return {
    v: STATUS_DOC_VERSION,
    alg: STATUS_ALG,
    kid,
    status: payload,
    sig: toBase64(signature),
  };
}

export async function verifyStatusDoc(envelope, servicePublicKey) {
  if (!envelope || envelope.v !== 1 || envelope.alg !== STATUS_ALG) {
    throw new Error("invalid-status");
  }
  const doc = envelope.status;
  if (!doc || doc.v !== STATUS_DOC_VERSION) throw new Error("invalid-status");
  if (!isValidStatus(doc.status) || typeof doc.checkedAt !== "string") {
    throw new Error("invalid-status");
  }
  const signature = fromBase64(envelope.sig);
  if (!(await verifyDer(servicePublicKey, statusSigningBytes(doc), signature))) {
    throw new Error("invalid-status-signature");
  }
  return doc;
}

export function validateLicensePayload(payload) {
  if (!payload || payload.v !== LICENSE_VERSION) throw new Error("invalid-license");
  if (typeof payload.licenseId !== "string" || payload.licenseId.length > 64) {
    throw new Error("invalid-license");
  }
  if (typeof payload.appId !== "string" || payload.appId.length === 0) {
    throw new Error("invalid-license");
  }
  if (!isValidType(payload.type)) throw new Error("invalid-license");
  if (!isValidStatus(payload.status)) throw new Error("invalid-license");
  if (!isIsoInstant(payload.issuedAt)) throw new Error("invalid-license");
  if (payload.expiresAt !== null && !isIsoInstant(payload.expiresAt)) {
    throw new Error("invalid-license");
  }
  if (typeof payload.deviceThumbprint !== "string" || payload.deviceThumbprint.length > 64) {
    throw new Error("invalid-license");
  }
  if (typeof payload.redemptionId !== "string" || payload.redemptionId.length > 64) {
    throw new Error("invalid-license");
  }
  if (payload.transferredFrom !== null && typeof payload.transferredFrom !== "string") {
    throw new Error("invalid-license");
  }
  if (!Number.isInteger(payload.secundarias) || payload.secundarias < 0 || payload.secundarias > 10) {
    throw new Error("invalid-license");
  }
  if (payload.devicePublicKey !== undefined && payload.devicePublicKey !== null) {
    if (typeof payload.devicePublicKey !== "string" || payload.devicePublicKey.length > 400) {
      throw new Error("invalid-license");
    }
  }
  return payload;
}

export function isIsoInstant(value) {
  if (typeof value !== "string" || value.length < 20 || value.length > 35) return false;
  const ms = Date.parse(value);
  return Number.isFinite(ms) && value.endsWith("Z");
}

export function deviceThumbprintOfSpkiBytes(spkiBytes) {
  // Se usa desde el cliente/servicio con el SPKI ya decodificado.
  return crypto.subtle.digest("SHA-256", spkiBytes).then((d) => toBase64Url(new Uint8Array(d)));
}
